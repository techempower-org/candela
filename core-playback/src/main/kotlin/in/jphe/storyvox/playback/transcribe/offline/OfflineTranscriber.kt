package `in`.jphe.storyvox.playback.transcribe.offline

import kotlinx.coroutines.flow.Flow

/**
 * Voice Notes (#1657, Phase 2b) — one punctuated transcript segment.
 * Times are millis from the start of the recording.
 */
data class TranscriptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Batch, file-based speech-to-text for recorded notes — the offline analog of
 * the streaming teleprompter path ([`in`.jphe.storyvox.playback.transcribe.MicCaptureProcessor]).
 *
 * Loads a sherpa-onnx `OfflineRecognizer` (Whisper base int8 — EN+ES, native
 * punctuation, javap-confirmed in the resolved 1.13.3 AAR, no dep bump) and
 * decodes an audio file into punctuated [TranscriptionSegment]s, **streaming
 * them as chunks complete** so a long meeting shows progress and stays within
 * a bounded memory budget (one ~30 s window decoded at a time — never the whole
 * hour at once).
 *
 * The recognizer + model are tens–hundreds of MB resident; the impl loads them
 * for the duration of a [transcribe] and releases them at the end. Runs off the
 * caller's thread (the impl uses `Dispatchers.Default`).
 */
interface OfflineTranscriber {

    /**
     * Decode [audioPath] to punctuated segments, emitting each as it completes.
     * [languageHint] is a Whisper language code (`"en"`, `"es"`, …); `null`
     * lets Whisper auto-detect. Throws / completes-empty on model-missing or
     * decode failure — callers ([TranscriptionWorker]) map that to a status,
     * never a crash.
     */
    fun transcribe(audioPath: String, languageHint: String?): Flow<TranscriptionSegment>

    /** True once the offline model is downloaded and ready to load. */
    fun isModelReady(): Boolean
}

/**
 * Pure window arithmetic for chunked transcription — extracted so the
 * bounded-memory chunking is unit-testable without sherpa or MediaCodec (the
 * device-validated pieces). Splits [totalSamples] of 16 kHz mono PCM into
 * consecutive windows of [windowSec] seconds; the last window is the remainder.
 */
object TranscriptionChunker {

    /** Default Whisper decode window — matches Whisper's native 30 s receptive field. */
    const val DEFAULT_WINDOW_SEC: Int = 30

    /** A half-open sample range `[start, end)` plus its wall-clock offset. */
    data class Window(val startSample: Int, val endSample: Int, val startMs: Long, val endMs: Long)

    /**
     * Windows covering `[0, totalSamples)`. Empty when [totalSamples] <= 0.
     * `endMs` of the last window is clamped to the true audio length so a
     * partial final window doesn't over-report duration.
     */
    fun windows(
        totalSamples: Int,
        sampleRate: Int = 16_000,
        windowSec: Int = DEFAULT_WINDOW_SEC,
    ): List<Window> {
        if (totalSamples <= 0 || sampleRate <= 0 || windowSec <= 0) return emptyList()
        val windowSamples = sampleRate.toLong() * windowSec
        val out = ArrayList<Window>()
        var start = 0
        while (start < totalSamples) {
            val end = minOf(start + windowSamples, totalSamples.toLong()).toInt()
            out += Window(
                startSample = start,
                endSample = end,
                startMs = start.toLong() * 1000 / sampleRate,
                endMs = end.toLong() * 1000 / sampleRate,
            )
            start = end
        }
        return out
    }
}
