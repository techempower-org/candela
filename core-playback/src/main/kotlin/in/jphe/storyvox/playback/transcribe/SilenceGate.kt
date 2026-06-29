package `in`.jphe.storyvox.playback.transcribe

/**
 * Issue #1368 — a hysteresis gate over per-frame audio loudness that decides
 * whether the streaming recognizer should be fed. Voice-activity-detection
 * lite: when the speaker pauses for [silenceToCloseMs] the gate **closes**
 * (the [MicCaptureProcessor] stops decoding, which is the CPU-hungry step —
 * Lucid's #1291 finding that VAD duty-cycling saves ~75% CPU on pauses); the
 * very next loud frame re-opens it ("auto-resume on voice activity").
 *
 * Loudness is a normalized RMS in `[0, 1]` (1.0 ≈ full-scale). The default
 * [rmsThreshold] of 0.012 sits around -38 dBFS — above room tone / breath,
 * below speech. The gate opens immediately on voice (no attack delay, so the
 * first syllable after a pause isn't clipped) and closes only after a
 * sustained quiet stretch (so inter-word gaps don't flap it).
 *
 * Pure and deterministic — the caller passes loudness + a monotonic clock —
 * so the open/close policy is unit-testable without a microphone. A genuine
 * Silero-VAD model (sherpa-onnx ships [`SileroVadModelConfig`]) is the
 * eventual upgrade for noisy rooms; this energy gate is the dependency-free
 * v1 and is enough for a phone held near the reader's mouth.
 */
class SilenceGate(
    private val rmsThreshold: Float = 0.012f,
    private val silenceToCloseMs: Long = 3_000L,
) {
    private var open = true
    private var quietSinceMs = -1L

    /** True if the recognizer should process this frame (gate open). */
    val isOpen: Boolean get() = open

    /**
     * Update with this frame's normalized [rms] loudness at [nowMs]; returns
     * whether the gate is open (feed the recognizer) afterwards.
     */
    fun update(rms: Float, nowMs: Long): Boolean {
        if (rms >= rmsThreshold) {
            // Voice present — open immediately, clear the quiet timer.
            open = true
            quietSinceMs = -1L
        } else {
            // Quiet frame — start (or continue) the trailing-silence timer.
            if (quietSinceMs < 0L) {
                quietSinceMs = nowMs
            } else if (nowMs - quietSinceMs >= silenceToCloseMs) {
                open = false
            }
        }
        return open
    }

    /** Reset to open with no pending silence (e.g. capture (re)started). */
    fun reset() {
        open = true
        quietSinceMs = -1L
    }
}
