package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1368 — the VAD-lite gate that pauses decoding on a long silence and
 * resumes on the next loud frame.
 */
class SilenceGateTest {

    private fun gate() = SilenceGate(rmsThreshold = 0.1f, silenceToCloseMs = 1_000L)

    @Test
    fun `starts open and stays open on voice`() {
        val g = gate()
        assertTrue(g.update(rms = 0.5f, nowMs = 0L))
        assertTrue(g.update(rms = 0.5f, nowMs = 100L))
    }

    @Test
    fun `closes only after sustained trailing silence`() {
        val g = gate()
        assertTrue(g.update(0.5f, 0L))      // voice
        assertTrue(g.update(0.0f, 100L))    // quiet begins
        assertTrue(g.update(0.0f, 900L))    // 800ms quiet — still open
        assertFalse(g.update(0.0f, 1_100L)) // 1000ms quiet — closes
    }

    @Test
    fun `reopens immediately on the next loud frame`() {
        val g = gate()
        g.update(0.5f, 0L)
        g.update(0.0f, 100L)
        assertFalse(g.update(0.0f, 1_200L)) // closed
        assertTrue(g.update(0.5f, 1_300L))  // voice returns → open at once
    }

    @Test
    fun `a brief gap does not accumulate toward closing`() {
        val g = gate()
        g.update(0.5f, 0L)
        g.update(0.0f, 100L)   // quiet timer starts at 100
        g.update(0.5f, 200L)   // voice resets the timer
        g.update(0.0f, 300L)   // quiet timer restarts at 300
        assertTrue(g.update(0.0f, 1_250L))  // only 950ms since 300 — still open
        assertFalse(g.update(0.0f, 1_350L)) // now 1050ms — closes
    }

    @Test
    fun `reset returns to open with no pending silence`() {
        val g = gate()
        g.update(0.5f, 0L)
        g.update(0.0f, 100L)
        g.update(0.0f, 1_200L) // closed
        g.reset()
        assertTrue(g.isOpen)
        // The previously-elapsed silence is forgotten — a fresh quiet window starts.
        assertTrue(g.update(0.0f, 1_300L))
    }
}
