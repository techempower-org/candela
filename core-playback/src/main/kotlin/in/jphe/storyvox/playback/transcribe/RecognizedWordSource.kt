package `in`.jphe.storyvox.playback.transcribe

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Issue #1291 — source of speech-recognized words for the voice-paced
 * teleprompter. The seam between audio→ASR and the pure alignment logic, so
 * [ForcedAligner] / [VoicePacedScroller] depend only on a `Flow<String>` and
 * stay fully unit-testable, while the device-gated capture+recognizer plugs
 * in behind this interface.
 *
 * ### Pipeline
 * ```
 * mic ──AudioRecord──▶ PcmDownsampler.toMono16k ──▶ sherpa-onnx OnlineStream
 *        (Phase 2)                (#1284)                .acceptWaveform
 *                                                              │
 *                          recognized words ◀── OnlineRecognizer.getResult
 *                                  │
 *   RecognizedWordSource.words ────┼──▶ ForcedAligner.onWord ──▶ positionChar
 *                                                                     │
 *                                          VoicePacedScroller.onPosition ──▶ scroll
 * ```
 *
 * ### Phase status
 * Phase 1 (this PR) lands the pure, tested consumers ([ForcedAligner],
 * [VoicePacedScroller]) plus this seam and a [NoOp]. The **live**
 * `MicCaptureProcessor` — `AudioRecord` capture at 44.1 kHz mono →
 * [PcmDownsampler] → a sherpa-onnx `OnlineRecognizer` streaming session,
 * gated on `RECORD_AUDIO` with graceful fallback to the manual-WPM
 * teleprompter (#1286) when the permission is denied — is **Phase 2**: it
 * depends on the exact sherpa-onnx ASR Kotlin API + on-device latency, which
 * (per the #1223 decision) must be validated on a device rather than wired
 * blind. It implements this interface, so nothing downstream changes when it
 * lands.
 */
interface RecognizedWordSource {

    /**
     * Stream of recognized words, emitted in spoken order. Implementations
     * should emit on finalized/stable words (not every interim hypothesis
     * character) so [ForcedAligner] advances on real tokens; the aligner
     * already tolerates the occasional misrecognition or partial word.
     */
    val words: Flow<String>

    /** Begin capture + recognition. Safe to call when already started. */
    fun start()

    /** Stop capture + recognition and release resources. Idempotent. */
    fun stop()

    companion object {
        /** Phase-1 default: emits nothing. Lets the teleprompter wire the
         *  voice-paced path end-to-end and fall back cleanly (manual WPM)
         *  until the device-validated capture+recognizer lands. */
        val NoOp: RecognizedWordSource = object : RecognizedWordSource {
            private val _words = MutableSharedFlow<String>()
            override val words: Flow<String> = _words.asSharedFlow()
            override fun start() {}
            override fun stop() {}
        }
    }
}
