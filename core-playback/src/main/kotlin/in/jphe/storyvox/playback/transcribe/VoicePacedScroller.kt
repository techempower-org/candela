package `in`.jphe.storyvox.playback.transcribe

/** Viewport / content geometry the scroller maps a text position onto. */
data class ScrollMetrics(
    val totalChars: Int,
    val contentHeightPx: Float,
    val viewportHeightPx: Float,
)

/**
 * @property targetScrollPx where the scroll container should be (top offset),
 *   clamped to a valid range.
 * @property velocityPxPerSec the speaker's current pace expressed as scroll
 *   velocity — lets the UI choose a smooth animation duration that tracks
 *   the speaker instead of snapping.
 */
data class ScrollTarget(
    val targetScrollPx: Float,
    val velocityPxPerSec: Float,
)

/**
 * Issue #1291 — turns the [ForcedAligner]'s character offset into a scroll
 * target for the voice-paced teleprompter. Three jobs, all pure math:
 *
 * 1. **Pace tracking** — estimate the speaker's reading rate (chars/ms) from
 *    successive positions, smoothed with an EMA so a single jumpy alignment
 *    doesn't whip the scroll.
 * 2. **Latency look-ahead** — STT lags the voice by ~200–500 ms, so we
 *    project the position forward by [lookaheadMs] at the current pace; the
 *    text the speaker is *about to say* is what should be centered.
 * 3. **Centered target + adaptive velocity** — place the (projected) position
 *    [centerBias] down the viewport and report the pace as a scroll velocity
 *    so the UI accelerates/decelerates with the speaker rather than snapping.
 *
 * Pure and deterministic (no Compose, no clock) — the caller passes the
 * timestamp and viewport metrics — so the pacing policy is unit-testable.
 */
class VoicePacedScroller(
    private val lookaheadMs: Long = 350,
    private val centerBias: Float = 0.4f,
    private val paceSmoothing: Float = 0.3f,
) {
    private var lastChar: Int = -1
    private var lastMs: Long = -1L
    private var paceCharsPerMs: Float = 0f

    fun reset() {
        lastChar = -1
        lastMs = -1L
        paceCharsPerMs = 0f
    }

    /**
     * Update with the aligner's [charOffset] at [nowMs] and the current
     * viewport [m]; returns the scroll target + adaptive velocity.
     */
    fun onPosition(charOffset: Int, nowMs: Long, m: ScrollMetrics): ScrollTarget {
        if (m.totalChars <= 0 || m.contentHeightPx <= 0f) {
            return ScrollTarget(0f, 0f)
        }

        // 1. Pace — only learn from forward motion over positive elapsed time
        // (backward jumps are re-syncs, not reading pace).
        if (lastChar in 0..charOffset && nowMs > lastMs && lastMs >= 0L) {
            val instant = (charOffset - lastChar).toFloat() / (nowMs - lastMs)
            paceCharsPerMs = if (paceCharsPerMs == 0f) instant
            else paceSmoothing * instant + (1f - paceSmoothing) * paceCharsPerMs
        }
        lastChar = charOffset
        lastMs = nowMs

        // 2. Latency look-ahead.
        val projectedChar = (charOffset + paceCharsPerMs * lookaheadMs)
            .coerceIn(0f, m.totalChars.toFloat())

        // 3. Centered target, clamped to a scrollable range.
        val fraction = projectedChar / m.totalChars
        val lineTopPx = fraction * m.contentHeightPx
        val maxScroll = (m.contentHeightPx - m.viewportHeightPx).coerceAtLeast(0f)
        val target = (lineTopPx - centerBias * m.viewportHeightPx).coerceIn(0f, maxScroll)

        // Velocity = reading pace mapped through chars→px, for adaptive smoothing.
        val pxPerChar = m.contentHeightPx / m.totalChars
        val velocityPxPerSec = paceCharsPerMs * pxPerChar * 1000f

        return ScrollTarget(targetScrollPx = target, velocityPxPerSec = velocityPxPerSec)
    }
}
