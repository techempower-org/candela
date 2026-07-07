package `in`.jphe.storyvox.feature.notes.record

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Voice Notes (#1657, Phase 2a) — the microphone-capture engine behind the
 * record screen. Wraps [MediaRecorder] (AAC / `.m4a`, mono, ~96 kbps) writing to
 * `filesDir/recordings/<noteId>.m4a`, and requests transient audio focus so the
 * app's own TTS pauses while recording.
 *
 * ## Why a process-wide @Singleton (not VM-scoped)
 * Capture must not be torn down by a configuration change (rotation) that
 * recreates [`NotesRecordViewModel`][`in`.jphe.storyvox.feature.notes.ui.NotesRecordViewModel].
 * Holding the recorder in a `@Singleton` lets a freshly-recreated VM re-attach to
 * the still-running session ([isRecording] + [elapsedMs] rehydrate its UI).
 * Timing is anchored to [SystemClock.elapsedRealtime] rather than a tick counter
 * so the elapsed clock is correct across that recreation and independent of the
 * UI ticker's cadence.
 *
 * ## Pause the app's TTS via audio focus
 * We request our OWN transient [AudioFocusRequest]; the framework then delivers
 * `AUDIOFOCUS_LOSS_TRANSIENT` to the playback service's focus listener, which
 * pauses the engine. We deliberately do NOT reuse
 * [`AudioFocusController`][`in`.jphe.storyvox.playback.AudioFocusController] —
 * its single request object is playback-owned.
 *
 * Every framework call is defensively wrapped: a `MediaRecorder` state-machine
 * slip must never crash the record screen — the take is salvaged or discarded.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var focusRequest: AudioFocusRequest? = null

    // Timing anchors (elapsedRealtime millis). See [computeElapsedMs].
    private var startRealtime = 0L
    private var pausedAccumMs = 0L
    private var pauseStartedAt = 0L
    private var pausedNow = false

    /** True while a session is open (between [start] and [stop]/[discard]). */
    val isRecording: Boolean get() = recorder != null

    /** True while a live session is paused. */
    val isPaused: Boolean get() = pausedNow

    /**
     * Configure + begin capturing [outputFile], and grab transient focus (pausing
     * TTS). Returns true on success; on any failure the recorder is released and
     * false is returned so the caller can abort without a half-open recorder.
     */
    fun start(outputFile: File): Boolean {
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioChannels(1) // mono — speech; halves size, P2b downmixes anyway
            mr.setAudioSamplingRate(SAMPLE_RATE_HZ)
            mr.setAudioEncodingBitRate(BITRATE_BPS)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setOutputFile(outputFile)
            mr.prepare()
            mr.start()
            recorder = mr
            output = outputFile
            startRealtime = SystemClock.elapsedRealtime()
            pausedAccumMs = 0L
            pauseStartedAt = 0L
            pausedNow = false
            requestTransientFocus()
            true
        } catch (e: Exception) {
            Log.w(TAG, "start failed for ${outputFile.name}: ${e.message}", e)
            runCatching { recorder?.release() }
            recorder = null
            output = null
            abandonFocus()
            false
        }
    }

    /**
     * The file the current session is writing to, or null when idle. Lives here
     * (the @Singleton), not in the VM, so it survives a VM recreation mid-record:
     * on stop the VM derives the note id from this file's name rather than a
     * VM-local field that a rotation would have wiped.
     */
    val currentOutput: File? get() = output

    /** Peak amplitude since the previous read (0..32767), or 0 if not recording. */
    val maxAmplitude: Int
        get() = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)

    /** Elapsed capture time in millis, excluding paused spans. 0 when idle. */
    fun elapsedMs(): Long {
        if (recorder == null) return 0L
        return computeElapsedMs(
            startRealtime = startRealtime,
            now = SystemClock.elapsedRealtime(),
            pausedAccumMs = pausedAccumMs,
            isPaused = pausedNow,
            pauseStartedAt = pauseStartedAt,
        )
    }

    fun pause() {
        if (recorder == null || pausedNow) return
        runCatching { recorder?.pause() }
            .onSuccess {
                pauseStartedAt = SystemClock.elapsedRealtime()
                pausedNow = true
            }
            .onFailure { Log.w(TAG, "pause ignored: ${it.message}") }
    }

    fun resume() {
        if (recorder == null || !pausedNow) return
        runCatching { recorder?.resume() }
            .onSuccess {
                pausedAccumMs += SystemClock.elapsedRealtime() - pauseStartedAt
                pausedNow = false
            }
            .onFailure { Log.w(TAG, "resume ignored: ${it.message}") }
    }

    /**
     * Stop + finalize the `.m4a` and release the mic/focus. Returns the recording
     * duration in millis, or -1 if the file was not written / is empty (a
     * too-short take), in which case the partial file is deleted so it can't leak
     * as an orphan or masquerade as valid audio.
     */
    fun stop(): Long {
        val mr = recorder ?: return -1L
        val file = output
        val duration = elapsedMs()
        val ok = try {
            mr.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "stop failed: ${e.message}")
            false
        } finally {
            runCatching { mr.release() }
            recorder = null
            abandonFocus()
        }
        val valid = ok && file != null && file.exists() && file.length() > 0L
        if (!valid) {
            runCatching { file?.takeIf(File::exists)?.delete() }
        }
        output = null
        pausedNow = false
        return if (valid) duration else -1L
    }

    /** Abort the take and delete any bytes written — used on cancel / VM clear. */
    fun discard() {
        val file = output
        recorder?.let { mr ->
            runCatching { mr.stop() }
            runCatching { mr.release() }
        }
        recorder = null
        abandonFocus()
        runCatching { file?.takeIf(File::exists)?.delete() }
        output = null
        pausedNow = false
    }

    private fun requestTransientFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            // No reaction to focus changes during capture (P2a): MediaRecorder
            // keeps capturing regardless; interruption handling is a follow-up.
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { req -> runCatching { am.abandonAudioFocusRequest(req) } }
        focusRequest = null
    }

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE_HZ = 44_100
        private const val BITRATE_BPS = 96_000
        private const val MAX_RAW_AMPLITUDE = 32_767f

        /**
         * Map a raw [MediaRecorder.getMaxAmplitude] reading (0..32767) to a
         * `[0f, 1f]` waveform bar height. A linear map reads as nearly-flat
         * because conversational speech rarely nears full scale; `sqrt` applies a
         * perceptual boost that lifts the low end so quiet talking still shows
         * motion. Pure — unit tested without a device.
         */
        internal fun normalizeAmplitude(raw: Int): Float {
            if (raw <= 0) return 0f
            val linear = (raw.toFloat() / MAX_RAW_AMPLITUDE).coerceIn(0f, 1f)
            return sqrt(linear).coerceIn(0f, 1f)
        }

        /**
         * Elapsed active-capture millis given the realtime anchors. Extracted pure
         * so the (otherwise [SystemClock]-bound) timer logic is unit tested:
         * subtract accumulated paused spans, plus the current open pause span when
         * [isPaused]. Clamped to ≥0 defensively.
         */
        internal fun computeElapsedMs(
            startRealtime: Long,
            now: Long,
            pausedAccumMs: Long,
            isPaused: Boolean,
            pauseStartedAt: Long,
        ): Long {
            val openPause = if (isPaused) (now - pauseStartedAt) else 0L
            return (now - startRealtime - pausedAccumMs - openPause).coerceAtLeast(0L)
        }
    }
}
