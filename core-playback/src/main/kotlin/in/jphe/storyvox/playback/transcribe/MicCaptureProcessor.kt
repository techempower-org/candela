package `in`.jphe.storyvox.playback.transcribe

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Issue #1368 — the **live** [RecognizedWordSource] for the voice-paced
 * teleprompter (the Phase-2 implementation the seam's kdoc anticipated). It
 * captures the microphone, runs sherpa-onnx streaming ASR, and emits the
 * recognized words (with confidence) that [VoicePacedScrollController] feeds
 * into [ForcedAligner] to drive the scroll.
 *
 * ### Pipeline
 * ```
 * AudioRecord (16 kHz mono PCM16, VOICE_RECOGNITION)
 *   → SilenceGate (skip decoding on a long pause — ~75% CPU saved, #1291)
 *   → OnlineStream.acceptWaveform → OnlineRecognizer.decode/getResult
 *   → AsrWordExtractor (growing hypothesis → newly-stable words)
 *   → words: Flow<RecognizedWord>   (confidence = asrConfidence(ysProbs))
 * ```
 *
 * The Android-specific surface is deliberately thin: capture + the recognizer
 * JNI calls live here, but every decision (which frames to feed, which words
 * are stable, how to score confidence) is delegated to the pure, unit-tested
 * helpers [SilenceGate] / [AsrWordExtractor] / [AsrAudio] / [asrConfidence].
 *
 * ### Graceful degradation
 * [start] is a no-op (emits nothing) when `RECORD_AUDIO` isn't granted or the
 * model isn't downloaded, so the reader simply falls back to the manual-WPM
 * teleprompter — no crash, no error surfaced. Capture runs on
 * [Dispatchers.IO]; [stop] cancels it and releases the recognizer + model
 * (tens of MB resident), [destroy] tears down the whole scope.
 *
 * ### Device-validation (#1368)
 * The recognizer config + model are wired against the verified sherpa-onnx
 * 1.13.3 API but their *behaviour* (latency, endpoint tuning, `ysProbs`
 * scale) must be validated on a device — consistent with how the #1291 author
 * deliberately deferred this live piece. The pure logic above is fully tested;
 * this class is the device-gated wiring.
 */
@Singleton
class MicCaptureProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelProvider: AsrModelProvider,
) : RecognizedWordSource {

    private val _words = MutableSharedFlow<RecognizedWord>(extraBufferCapacity = 64)
    override val words: Flow<RecognizedWord> = _words.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    override fun start() {
        if (job?.isActive == true) return
        if (!hasRecordPermission()) {
            Log.i(TAG, "voice-paced: RECORD_AUDIO not granted — staying silent")
            return
        }
        val model = modelProvider.readyModel()
        if (model == null) {
            Log.i(TAG, "voice-paced: ASR model not downloaded — staying silent")
            return
        }
        job = scope.launch { runCapture(model) }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    /** Release the capture scope entirely; the instance can't be reused after. */
    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // start() runtime-checks RECORD_AUDIO before launching this
    private suspend fun runCapture(model: AsrModel) {
        val recognizer = buildRecognizer(model) ?: return
        val stream = recognizer.createStream()
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            stream.release(); recognizer.release(); return
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_SAMPLES * BYTES_PER_SAMPLE * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release(); stream.release(); recognizer.release(); return
        }

        val frame = ShortArray(FRAME_SAMPLES)
        val gate = SilenceGate()
        val extractor = AsrWordExtractor()
        var feeding = false
        try {
            record.startRecording()
            while (coroutineContext.isActive) {
                val n = record.read(frame, 0, frame.size)
                if (n <= 0) continue
                val open = gate.update(AsrAudio.rms(frame, n), SystemClock.elapsedRealtime())
                if (open) {
                    feeding = true
                    stream.acceptWaveform(AsrAudio.toFloatPcm(frame, n), SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val result = recognizer.getResult(stream)
                    val confidence = asrConfidence(result.ysProbs)
                    if (recognizer.isEndpoint(stream)) {
                        emitWords(extractor.flush(result.text), confidence)
                        recognizer.reset(stream)
                    } else {
                        emitWords(extractor.newWords(result.text), confidence)
                    }
                } else if (feeding) {
                    // Just crossed into a long pause: finalize the in-flight
                    // utterance so its held-back tail word still reaches the
                    // aligner, then idle (no decoding) until voice returns.
                    feeding = false
                    val result = recognizer.getResult(stream)
                    emitWords(extractor.flush(result.text), asrConfidence(result.ysProbs))
                    recognizer.reset(stream)
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "voice-paced capture aborted: ${t.message}")
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            runCatching { stream.release() }
            runCatching { recognizer.release() }
        }
    }

    private suspend fun emitWords(words: List<String>, confidence: Float) {
        for (w in words) _words.emit(RecognizedWord(text = w, confidence = confidence))
    }

    /**
     * Build the streaming transducer recognizer from [model]'s file paths.
     * Returns null on any init failure (corrupt/incompatible model) so capture
     * degrades to silent rather than crashing. Leaves `modelType` /
     * `decodingMethod` at sherpa's defaults (transducer is auto-detected;
     * greedy search) — see the device-validation note in the class kdoc.
     */
    private fun buildRecognizer(model: AsrModel): OnlineRecognizer? = try {
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = model.encoder,
                    decoder = model.decoder,
                    joiner = model.joiner,
                ),
                tokens = model.tokens,
                numThreads = 2,
            ),
            enableEndpoint = true,
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            ),
        )
        OnlineRecognizer(config = config)
    } catch (t: Throwable) {
        Log.w(TAG, "voice-paced recognizer init failed: ${t.message}")
        null
    }

    private companion object {
        const val TAG = "MicCaptureProcessor"

        /** sherpa-onnx streaming models expect 16 kHz mono. */
        const val SAMPLE_RATE = 16_000

        /** Kaldi-style fbank feature dimension the streaming models use. */
        const val FEATURE_DIM = 80

        const val BYTES_PER_SAMPLE = 2

        /** 100 ms read granularity — responsive VAD without per-sample churn. */
        const val FRAME_SAMPLES = 1_600
    }
}
