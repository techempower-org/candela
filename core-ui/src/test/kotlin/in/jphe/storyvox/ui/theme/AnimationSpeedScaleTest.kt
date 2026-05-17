package `in`.jphe.storyvox.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #589 — regression coverage for the animation-speed scaling
 * math used by `tweenScaled(N)`. Pure-function test so it doesn't
 * need Robolectric / a Compose test rule.
 */
class AnimationSpeedScaleTest {

    @Test
    fun `scale of zero collapses every duration to zero`() {
        // Off (LocalReducedMotion-equivalent). Durations become 0
        // regardless of input.
        assertEquals(0, scaleDurationMs(280, 0f))
        assertEquals(0, scaleDurationMs(1, 0f))
        assertEquals(0, scaleDurationMs(10_000, 0f))
    }

    @Test
    fun `scale of one is the identity`() {
        // Normal — durations unchanged.
        assertEquals(280, scaleDurationMs(280, 1f))
        assertEquals(180, scaleDurationMs(180, 1f))
        assertEquals(360, scaleDurationMs(360, 1f))
    }

    @Test
    fun `scale greater than one shortens durations`() {
        // Brisk (1.5×) — durations divided by 1.5.
        assertEquals((280 / 1.5f).toInt(), scaleDurationMs(280, 1.5f))
        // Fast (2×) — durations halved.
        assertEquals(140, scaleDurationMs(280, 2f))
        assertEquals(90, scaleDurationMs(180, 2f))
    }

    @Test
    fun `scale less than one lengthens durations`() {
        // Slow (0.5×) — durations doubled.
        assertEquals(560, scaleDurationMs(280, 0.5f))
        assertEquals(360, scaleDurationMs(180, 0.5f))
    }

    @Test
    fun `negative scales clamp to zero (defensive)`() {
        // Defensive: if a future caller fat-fingers a negative value,
        // we collapse to zero (instant) rather than producing
        // gibberish durations.
        assertEquals(0, scaleDurationMs(280, -1f))
        assertEquals(0, scaleDurationMs(280, Float.NEGATIVE_INFINITY))
    }

    @Test
    fun `scaled durations of one ms remain at least one ms when not off`() {
        // Floor at 1 ms so we don't emit `tween(0)` on a Brisk/Fast
        // pass over an already-tiny duration — Compose's `tween(0)`
        // is a degenerate spec.
        assertEquals(1, scaleDurationMs(1, 2f))
        assertEquals(1, scaleDurationMs(2, 4f))
    }
}
