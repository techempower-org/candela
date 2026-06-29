package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1291 — the pure pacing math behind the voice-paced scroll:
 * pace estimation, latency look-ahead, centered target, and clamping.
 */
class VoicePacedScrollerTest {

    // 1000 chars over 10000px content in a 1000px viewport → 10 px/char,
    // max scroll 9000px, current line parked 40% down (centerBias).
    private val m = ScrollMetrics(totalChars = 1000, contentHeightPx = 10_000f, viewportHeightPx = 1_000f)

    @Test
    fun `first position has no pace and centers with bias`() {
        val s = VoicePacedScroller()
        val t = s.onPosition(charOffset = 100, nowMs = 1_000, m = m)
        // fraction .1 → 1000px line top, minus 0.4*1000 bias = 600.
        assertEquals(600f, t.targetScrollPx, 0.5f)
        assertEquals(0f, t.velocityPxPerSec, 0.5f)
    }

    @Test
    fun `pace drives latency look-ahead and adaptive velocity`() {
        val s = VoicePacedScroller()
        s.onPosition(100, 1_000, m) // seed
        val t = s.onPosition(200, 2_000, m) // 0.1 chars/ms
        // projected = 200 + 0.1*350 = 235 → 2350px line top − 400 bias = 1950.
        assertEquals(1950f, t.targetScrollPx, 1f)
        // velocity = 0.1 chars/ms * 10 px/char * 1000 = 1000 px/s.
        assertEquals(1000f, t.velocityPxPerSec, 1f)
    }

    @Test
    fun `target clamps to zero at the top`() {
        val s = VoicePacedScroller()
        val t = s.onPosition(0, 1_000, m)
        assertEquals(0f, t.targetScrollPx, 0.001f)
    }

    @Test
    fun `target clamps to max scroll at the bottom`() {
        val s = VoicePacedScroller()
        val t = s.onPosition(990, 1_000, m)
        // 0.99*10000 − 400 = 9500, clamped to maxScroll 9000.
        assertEquals(9000f, t.targetScrollPx, 0.001f)
    }

    @Test
    fun `degenerate metrics yield a safe zero target`() {
        val s = VoicePacedScroller()
        val t = s.onPosition(100, 1_000, ScrollMetrics(0, 0f, 1_000f))
        assertEquals(0f, t.targetScrollPx, 0.001f)
        assertEquals(0f, t.velocityPxPerSec, 0.001f)
    }

    @Test
    fun `a backward re-sync jump does not corrupt the learned pace`() {
        val s = VoicePacedScroller()
        s.onPosition(100, 1_000, m)
        s.onPosition(200, 2_000, m) // pace = 0.1 chars/ms
        val t = s.onPosition(150, 3_000, m) // backward (re-sync) — ignored for pace
        // pace stays 0.1 → velocity stays positive ~1000, never goes negative.
        assertEquals(1000f, t.velocityPxPerSec, 1f)
    }
}
