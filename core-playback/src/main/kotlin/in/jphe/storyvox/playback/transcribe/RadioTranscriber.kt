package `in`.jphe.storyvox.playback.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Issue #1223 — live speech-to-text for radio streams (read-along for
 * audio). Abstraction boundary between the radio playback path and the
 * on-device recognizer, so the (device-validated, Phase-2) sherpa-onnx
 * `OnlineRecognizer` implementation can be swapped in without the caller
 * — or the reader/captions UI — depending on the native API.
 *
 * Lifecycle: [start] when a radio stream begins playing with captions
 * enabled → [acceptPcm] for every decoded audio buffer the Media3
 * `AudioProcessor` tap copies out → [stop] on pause / chapter change /
 * teardown (the recognizer + model are tens of MB resident and must be
 * released, see the research notes on #1223).
 *
 * Implementations MUST be safe to drive from the audio thread:
 * [acceptPcm] runs in the ExoPlayer audio pipeline and must not block on
 * model inference (offload to a worker; emit results on [segments]).
 */
interface RadioTranscriber {

    /**
     * Stream of transcript updates. Interim (`isFinal = false`) segments
     * update the live caption line; final segments (`isFinal = true`) are
     * committed — the point at which the reader read-along could append
     * them to the chapter body (Phase 2). Replay is 0: late collectors
     * see only new segments, matching live-caption semantics.
     */
    val segments: Flow<TranscriptSegment>

    /**
     * Begin a recognition session. [languageHint] is a BCP-47-ish code
     * (e.g. `"en"`) seeded from the RadioBrowser station `language` field
     * to pick the right model; `null` falls back to the default model.
     */
    fun start(languageHint: String?)

    /**
     * Feed one buffer of decoded PCM. Format is whatever ExoPlayer
     * produced; the implementation normalises via [PcmDownsampler] before
     * the recognizer. No-op if no session is active.
     */
    fun acceptPcm(pcm: ByteArray, srcRateHz: Int, channels: Int)

    /** End the session and release the recognizer + model. Idempotent. */
    fun stop()

    companion object {
        /**
         * Phase-1 no-op transcriber: emits nothing, holds no model. The
         * default binding until the Phase-2 sherpa-onnx implementation
         * lands, so the radio path and DI graph compile and run unchanged
         * with the feature off.
         */
        val NoOp: RadioTranscriber = object : RadioTranscriber {
            private val _segments = MutableSharedFlow<TranscriptSegment>()
            override val segments: Flow<TranscriptSegment> = _segments.asSharedFlow()
            override fun start(languageHint: String?) {}
            override fun acceptPcm(pcm: ByteArray, srcRateHz: Int, channels: Int) {}
            override fun stop() {}
        }
    }
}

/**
 * One transcript update.
 *
 * @property text the recognized text. For an interim segment this is the
 *   recognizer's current best hypothesis for the in-flight utterance; for
 *   a final segment it's the committed utterance.
 * @property isFinal `true` once the recognizer endpoints the utterance and
 *   commits it; `false` for live interim hypotheses that may still change.
 */
data class TranscriptSegment(
    val text: String,
    val isFinal: Boolean,
)
