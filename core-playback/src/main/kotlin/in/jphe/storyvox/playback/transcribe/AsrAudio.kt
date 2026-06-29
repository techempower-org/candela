package `in`.jphe.storyvox.playback.transcribe

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Issue #1368 — pure, allocation-light helpers for the live mic→ASR path
 * ([MicCaptureProcessor]). Kept Android-free (operate on `ShortArray` /
 * `FloatArray`, not `AudioRecord`) so the signal math and the
 * confidence mapping are unit-testable without a device — the one place
 * worth locking down before the device-validated recognizer wiring.
 */
object AsrAudio {

    /** Full-scale magnitude of signed 16-bit PCM. */
    private const val INT16_FULL_SCALE: Float = 32_768f

    /**
     * Normalized RMS loudness of the first [length] samples of signed-16-bit
     * [pcm], in `[0, 1]` (1.0 ≈ full scale). Feeds [SilenceGate]. Returns 0
     * for an empty/zero-length frame.
     */
    fun rms(pcm: ShortArray, length: Int): Float {
        val n = length.coerceAtMost(pcm.size)
        if (n <= 0) return 0f
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = pcm[i] / INT16_FULL_SCALE
            sumSq += (s * s).toDouble()
        }
        return sqrt(sumSq / n).toFloat()
    }

    /**
     * Convert the first [length] samples of signed-16-bit [pcm] to float in
     * `[-1, 1)` — the format sherpa-onnx's `OnlineStream.acceptWaveform`
     * expects. Allocates one [FloatArray] of size [length].
     */
    fun toFloatPcm(pcm: ShortArray, length: Int): FloatArray {
        val n = length.coerceAtMost(pcm.size).coerceAtLeast(0)
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = pcm[i] / INT16_FULL_SCALE
        return out
    }
}

/**
 * Issue #1368 — collapse a recognizer result's per-token vocab log-probs
 * (sherpa-onnx `OnlineRecognizerResult.ysProbs`) into a single utterance
 * confidence in `[0, 1]`, which [ForcedAligner.onWord] uses to *hold* the
 * scroll on low-confidence speech (coughs / "umm"s / off-script asides —
 * Lucid's #1291 finding #4).
 *
 * `ysProbs` are natural-log probabilities (≤ 0); the geometric mean of the
 * per-token probabilities — `exp(mean(logProbs))` — is the standard
 * sequence-confidence summary and is robust to utterance length. An empty
 * array means the recognizer reported no probabilities, so we return `1f`
 * (don't gate) — matching [RecognizedWord]'s "confidence unavailable"
 * default.
 *
 * NOTE (device-validation, #1368): the exact `ysProbs` scale should be
 * confirmed on-device. If they ever arrive as already-linear probabilities
 * rather than log-probs this mapping over-gates, in which case the
 * [MicCaptureProcessor] should emit `1f` and lean entirely on the aligner's
 * fuzzy matcher. Over-gating only freezes the scroll (graceful), never crashes.
 */
fun asrConfidence(ysProbs: FloatArray): Float {
    if (ysProbs.isEmpty()) return 1f
    var sum = 0f
    for (p in ysProbs) sum += p
    val mean = sum / ysProbs.size
    return exp(mean).coerceIn(0f, 1f)
}
