package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1315 — timing / boundary edge cases for the voice-paced scroll math,
 * complementing [VoicePacedScrollerTest]: degenerate metrics, non-advancing or
 * backward timestamps (no divide-by-zero, no pace corruption), look-ahead
 * projection clamped at end-of-text, content shorter than the viewport,
 * reset(), and a custom centerBias.
 */
class VoicePacedScrollerTimingTest {

    // 1000 chars over 10000px content in a 1000px viewport → 10 px/char,
    // max scroll 9000px, line parked 40% down by default (centerBias).
    private val m = ScrollMetrics(totalChars = 1000, contentHeightPx = 10_000f, viewportHeightPx = 1_000f)

    @Test fun `zero content height yields a safe zero target`() {
        val t = VoicePacedScroller().onPosition(100, 1_000, ScrollMetrics(1000, 0f, 1_000f))
        assertEquals(0f, t.targetScrollPx, 0.001f)
        assertEquals(0f, t.velocityPxPerSec, 0.001f)
    }

    @Test fun `negative total chars yields a safe zero target`() {
        val t = VoicePacedScroller().onPosition(100, 1_000, ScrollMetrics(-5, 10_000f, 1_000f))
        assertEquals(0f, t.targetScrollPx, 0.001f)
        assertEquals(0f, t.velocityPxPerSec, 0.001f)
    }

    @Test fun `a non-advancing timestamp does not learn pace or divide by zero`() {
        val s = VoicePacedScroller()
        s.onPosition(100, 1_000, m)         // seed
        val t = s.onPosition(200, 1_000, m) // same nowMs → elapsed 0 → pace update skipped
        assertEquals(0f, t.velocityPxPerSec, 0.001f)
        // projected = 200 + 0 → fraction .2 → 2000px − 0.4*1000 bias = 1600.
        assertEquals(1600f, t.targetScrollPx, 0.5f)
    }

    @Test fun `a backward timestamp does not learn pace`() {
        val s = VoicePacedScroller()
        s.onPosition(100, 2_000, m)         // seed at t=2000
        val t = s.onPosition(200, 1_000, m) // time goes backward → pace update skipped
        assertEquals(0f, t.velocityPxPerSec, 0.001f)
    }

    @Test fun `look-ahead projection clamps at end of text`() {
        val s = VoicePacedScroller()
        s.onPosition(900, 1_000, m)         // seed
        val t = s.onPosition(990, 1_100, m) // 0.9 chars/ms; 990 + 0.9*350 = 1305 → clamp to 1000
        // fraction 1.0 → 10000px − 400 bias = 9600 → clamp to maxScroll 9000.
        assertEquals(9000f, t.targetScrollPx, 0.5f)
    }

    @Test fun `content shorter than the viewport keeps the target at zero`() {
        val short = ScrollMetrics(totalChars = 1000, contentHeightPx = 500f, viewportHeightPx = 1_000f)
        val t = VoicePacedScroller().onPosition(500, 1_000, short) // maxScroll coerced to 0
        assertEquals(0f, t.targetScrollPx, 0.001f)
    }

    @Test fun `reset clears the learned pace`() {
        val s = VoicePacedScroller()
        s.onPosition(100, 1_000, m)
        s.onPosition(200, 2_000, m)         // pace 0.1 chars/ms
        s.reset()
        val t = s.onPosition(300, 3_000, m) // lastChar/lastMs cleared → no learn this call
        assertEquals(0f, t.velocityPxPerSec, 0.001f)
        // projected = 300 + 0 → fraction .3 → 3000px − 400 = 2600.
        assertEquals(2600f, t.targetScrollPx, 0.5f)
    }

    @Test fun `zero center bias parks the line at the viewport top`() {
        val t = VoicePacedScroller(centerBias = 0f).onPosition(100, 1_000, m)
        // fraction .1 → 1000px line top, no bias subtracted.
        assertEquals(1000f, t.targetScrollPx, 0.5f)
    }
}
