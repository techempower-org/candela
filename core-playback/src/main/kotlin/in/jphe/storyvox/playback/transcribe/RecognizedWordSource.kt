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
 * `MicCaptureProcessor` is **Phase 2**: `AudioRecord` capture at 44.1 kHz
 * mono → [PcmDownsampler] → a **Silero-VAD-gated** sherpa-onnx
 * `OnlineRecognizer` streaming session, **hotword-biased to the next ~30
 * reference words** (Lucid's #1291 findings: VAD duty-cycling saves ~75% CPU
 * on pauses; hotword bias lets a compact model match known text). Gated on
 * `RECORD_AUDIO` with graceful fallback to the manual-WPM teleprompter
 * (#1286) when denied. It depends on the exact sherpa-onnx ASR Kotlin API +
 * on-device latency, which (per the #1223 decision) must be validated on a
 * device rather than wired blind — ideally reusing the #1223 recognizer
 * wiring. It implements this interface, so nothing downstream changes when it
 * lands.
 */
interface RecognizedWordSource {

    /**
     * Stream of recognized words (text + recognizer confidence), in spoken
     * order. Implementations should emit on finalized/stable words (not every
     * interim hypothesis character) so [ForcedAligner] advances on real
     * tokens; [RecognizedWord.confidence] feeds the aligner's
     * hold-on-low-confidence gate (#1291, Lucid finding #4). sherpa-onnx
     * exposes per-token vocab log-probs for this in recent builds; emit 1f
     * when confidence is unavailable.
     */
    val words: Flow<RecognizedWord>

    /** Begin capture + recognition. Safe to call when already started. */
    fun start()

    /** Stop capture + recognition and release resources. Idempotent. */
    fun stop()

    companion object {
        /** Phase-1 default: emits nothing. Lets the teleprompter wire the
         *  voice-paced path end-to-end and fall back cleanly (manual WPM)
         *  until the device-validated capture+recognizer lands. */
        val NoOp: RecognizedWordSource = object : RecognizedWordSource {
            private val _words = MutableSharedFlow<RecognizedWord>()
            override val words: Flow<RecognizedWord> = _words.asSharedFlow()
            override fun start() {}
            override fun stop() {}
        }
    }
}

/**
 * One recognized word and the recognizer's confidence in it (0..1, 1f when
 * unavailable). Confidence gates the aligner's hold-on-low-confidence rule.
 */
data class RecognizedWord(
    val text: String,
    val confidence: Float = 1f,
)
