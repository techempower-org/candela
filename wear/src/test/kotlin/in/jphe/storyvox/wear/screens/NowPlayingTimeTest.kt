package `in`.jphe.storyvox.wear.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1404 — unit tests for [formatPlaybackTime], the elapsed/remaining
 * readout formatter on the Wear now-playing surface. Pure logic, so it's
 * verified here rather than via a Compose render.
 */
class NowPlayingTimeTest {

    @Test fun `formats zero as minutes and seconds`() {
        assertEquals("0:00", formatPlaybackTime(0L))
    }

    @Test fun `pads single-digit seconds`() {
        assertEquals("0:07", formatPlaybackTime(7_000L))
    }

    @Test fun `formats sub-hour duration without an hours field`() {
        assertEquals("1:30", formatPlaybackTime(90_000L))
        assertEquals("12:30", formatPlaybackTime(750_000L))
    }

    @Test fun `formats exactly one hour with padded minutes and seconds`() {
        assertEquals("1:00:00", formatPlaybackTime(3_600_000L))
    }

    @Test fun `formats hours minutes and seconds together`() {
        assertEquals("1:02:03", formatPlaybackTime(3_723_000L))
    }

    @Test fun `truncates the sub-second remainder`() {
        assertEquals("0:01", formatPlaybackTime(1_999L))
    }

    @Test fun `clamps negative input to zero`() {
        assertEquals("0:00", formatPlaybackTime(-5_000L))
    }
}
