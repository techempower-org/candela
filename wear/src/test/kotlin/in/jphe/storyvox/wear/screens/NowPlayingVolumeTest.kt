package `in`.jphe.storyvox.wear.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1401 — unit tests for [volumeFraction], the STREAM_MUSIC level→arc-fill
 * mapping behind the transient volume indicator on the Wear now-playing surface.
 * Pure logic, so it's verified here rather than through a Compose render.
 */
class NowPlayingVolumeTest {

    @Test fun `zero volume is an empty arc`() {
        assertEquals(0f, volumeFraction(0, 15), 0f)
    }

    @Test fun `full volume fills the arc`() {
        assertEquals(1f, volumeFraction(15, 15), 0f)
    }

    @Test fun `mid volume is a proportional fraction`() {
        assertEquals(0.5f, volumeFraction(5, 10), 1e-6f)
    }

    @Test fun `level above max clamps to full`() {
        assertEquals(1f, volumeFraction(20, 15), 0f)
    }

    @Test fun `negative level clamps to empty`() {
        assertEquals(0f, volumeFraction(-3, 15), 0f)
    }

    @Test fun `non-positive max is treated as empty rather than dividing by zero`() {
        assertEquals(0f, volumeFraction(5, 0), 0f)
    }
}
