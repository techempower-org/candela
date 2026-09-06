package `in`.jphe.storyvox.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1696 (Gemini review of #1618) — the post-stop shake-to-revive grace window
 * must be dropped once the user takes over manually, or a stray shake inside
 * the 60s window would call `resume()` + re-arm the OLD fired mode, clobbering
 * a manual resume or a freshly-started timer.
 *
 * [shouldClearGraceWindow] is the pure gate `StoryvoxPlaybackService`
 * .refreshShakeListening consults before computing [shouldReviveOnShake] /
 * [shouldListenInGraceWindow]. These pin it, and pin that a *legitimate* open
 * window (timer just fired: paused, no timer running) is preserved.
 */
class ShakeReviveGraceWindowTest {

    @Test
    fun `legitimate open window is preserved`() {
        // Timer fired => playback paused (isPlaying=false), no timer running.
        assertFalse(shouldClearGraceWindow(isPlaying = false, sleepTimerRunning = false))
    }

    @Test
    fun `manual resume clears the window`() {
        assertTrue(shouldClearGraceWindow(isPlaying = true, sleepTimerRunning = false))
    }

    @Test
    fun `a newly started timer clears the window`() {
        assertTrue(shouldClearGraceWindow(isPlaying = false, sleepTimerRunning = true))
    }

    @Test
    fun `resume with a new timer clears the window`() {
        assertTrue(shouldClearGraceWindow(isPlaying = true, sleepTimerRunning = true))
    }

    /**
     * The bug scenario, expressed against the two gates together: after the
     * window is cleared (msSinceFired reset to null), a shake must NOT revive.
     */
    @Test
    fun `once cleared, a shake in the old window no longer revives`() {
        val graceMs = 60_000L
        // Before clear: 5s since fire, inside the window -> would revive.
        assertTrue(shouldReviveOnShake(msSinceFiredMs = 5_000L, graceWindowMs = graceMs))
        // Manual resume => window cleared => msSinceFired becomes null.
        assertTrue(shouldClearGraceWindow(isPlaying = true, sleepTimerRunning = false))
        assertFalse(shouldReviveOnShake(msSinceFiredMs = null, graceWindowMs = graceMs))
    }
}
