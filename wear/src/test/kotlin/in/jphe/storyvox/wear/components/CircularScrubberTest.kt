package `in`.jphe.storyvox.wear.components

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1031 — geometry behind tap-to-scrub on the circular ring.
 *
 * [tapFraction] maps a tap inside the ring box to a 0f..1f fraction, with 0f at
 * 12-o'clock (where the progress arc starts, `startAngle = 270f`) and growing
 * clockwise. `Offset` is a pure value class, so these run on the JVM without
 * Robolectric — same seam pattern as [in.jphe.storyvox.wear.playback.NodeSelectionTest].
 *
 * Box is 200×200, so center is (100, 100).
 */
class CircularScrubberTest {

    private val size = 200f
    private val tol = 0.001f

    @Test fun `tap at 12 o'clock (top) is fraction 0`() {
        assertEquals(0f, tapFraction(Offset(100f, 0f), size, size), tol)
    }

    @Test fun `tap at 3 o'clock (right) is a quarter`() {
        assertEquals(0.25f, tapFraction(Offset(200f, 100f), size, size), tol)
    }

    @Test fun `tap at 6 o'clock (bottom) is a half`() {
        assertEquals(0.5f, tapFraction(Offset(100f, 200f), size, size), tol)
    }

    @Test fun `tap at 9 o'clock (left) is three quarters`() {
        assertEquals(0.75f, tapFraction(Offset(0f, 100f), size, size), tol)
    }

    @Test fun `fraction grows clockwise — 1 30 position is between top and right`() {
        // A point up-and-to-the-right (between 12 and 3 o'clock) must land
        // strictly between 0 and 0.25, proving the sweep direction is clockwise.
        val f = tapFraction(Offset(150f, 50f), size, size)
        assertEquals(0.125f, f, tol)
    }

    @Test fun `result is always within 0 to 1`() {
        // Even a tap at the exact center (degenerate, zero radius) stays in range
        // rather than producing NaN/out-of-bounds.
        val f = tapFraction(Offset(100f, 100f), size, size)
        assertEquals(true, f in 0f..1f)
    }
}
