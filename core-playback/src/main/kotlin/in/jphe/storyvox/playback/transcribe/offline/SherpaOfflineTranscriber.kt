package `in`.jphe.storyvox.playback.transcribe.offline

import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Voice Notes (#1657, Phase 2b) — the sherpa-onnx `OfflineRecognizer`
 * implementation of [OfflineTranscriber] (Whisper base int8; javap-confirmed in
 * the resolved 1.13.3 AAR — no dependency bump). Filesystem-path model loading,
 * exactly like `MicCaptureProcessor`'s streaming `OnlineRecognizer(config = …)`.
 *
 * Streams the file through [AudioFileDecoder.streamMono16kWindows] (#1669):
 * each ~30 s window of 16 kHz mono float is recognised as soon as the codec
 * produces it, emitting a punctuated [TranscriptionSegment] per window — so a
 * multi-hour recording streams progress with memory bounded to one window
 * (never the whole file's PCM). The recognizer +
 * model are released when the flow completes or is cancelled.
 *
 * Device-validated wiring (per `MicCaptureProcessor`'s posture): the config is
 * against the verified 1.13.3 API but decode latency / accuracy must be
 * confirmed on a device. `numThreads`, `decodingMethod`, tail padding are left
 * at sherpa defaults for now.
 */
@Singleton
class SherpaOfflineTranscriber @Inject constructor(
    private val modelProvider: TranscriptionModelProvider,
) : OfflineTranscriber {

    override fun isModelReady(): Boolean = modelProvider.isReady()

    override fun transcribe(audioPath: String, languageHint: String?): Flow<TranscriptionSegment> = flow {
        val model = modelProvider.readyModel()
            ?: error("offline transcription model not downloaded")
        val recognizer = buildRecognizer(model, languageHint)
            ?: error("OfflineRecognizer init failed")
        try {
            // #1669 — streaming decode: each ~30 s window is recognised as soon
            // as the codec produces it; the whole-file PCM is never resident.
            AudioFileDecoder.streamMono16kWindows(
                path = audioPath,
                windowSamples = SAMPLE_RATE * TranscriptionChunker.DEFAULT_WINDOW_SEC,
            ) { window ->
                currentCoroutineContext().ensureActive() // honor cancellation between windows
                val stream = recognizer.createStream()
                val text = try {
                    stream.acceptWaveform(window.samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    recognizer.getResult(stream).text.trim()
                } finally {
                    stream.release()
                }
                if (text.isNotEmpty()) {
                    emit(TranscriptionSegment(window.startMs, window.endMs, text))
                }
            }
        } finally {
            recognizer.release()
        }
    }.flowOn(Dispatchers.Default) // CPU-bound inference off the collector's thread (#585)

    private fun buildRecognizer(model: OfflineAsrModel, languageHint: String?): OfflineRecognizer? = try {
        OfflineRecognizer(
            config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = model.encoder,
                        decoder = model.decoder,
                        // "" → Whisper auto-detects the language.
                        language = languageHint ?: "",
                        task = "transcribe",
                    ),
                    tokens = model.tokens,
                    numThreads = NUM_THREADS,
                ),
            ),
        )
    } catch (t: Throwable) {
        Log.w(TAG, "OfflineRecognizer init failed: ${t.message}")
        null
    }

    private companion object {
        const val TAG = "SherpaOfflineTranscriber"

        /** Whisper (like sherpa's streaming models) expects 16 kHz mono. */
        const val SAMPLE_RATE = 16_000

        /** Kaldi-style fbank feature dimension. */
        const val FEATURE_DIM = 80

        const val NUM_THREADS = 2
    }
}
