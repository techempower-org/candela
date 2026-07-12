package `in`.jphe.storyvox.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinned decision tables for the sleep-timer auto-arm (#1574) and
 * shake-to-extend (#1595) gates. Pure JVM — no Hilt, no MediaSession,
 * no sherpa — so the exact rules the service depends on are verifiable
 * on the CI test-compile path.
 */
class SleepTimerAutoArmTest {

    // ── #1574 — DND auto-arm ───────────────────────────────────────

    @Test fun `arms when DND active, playing, and no timer running`() {
        assertTrue(shouldArmBedtimeSleep(dndActive = true, isPlaying = true, sleepTimerRunning = false))
    }

    @Test fun `does not arm when DND is not active`() {
        // The #1574 ② live suspect: currentInterruptionFilter read back as
        // INTERRUPTION_FILTER_ALL (stale) at onReceive time → dndActive
        // false → no arm. Instrumentation now logs the raw filter so an
        // on-device repro can confirm/deny this without a code change.
        assertFalse(shouldArmBedtimeSleep(dndActive = false, isPlaying = true, sleepTimerRunning = false))
    }

    @Test fun `does not arm when not playing`() {
        assertFalse(shouldArmBedtimeSleep(dndActive = true, isPlaying = false, sleepTimerRunning = false))
    }

    @Test fun `does not re-arm when a timer is already running`() {
        assertFalse(shouldArmBedtimeSleep(dndActive = true, isPlaying = true, sleepTimerRunning = true))
    }

    @Test fun `isPlaying true is the whole gate — refutes the buffering-gap hypothesis`() {
        // #557 invariant: the engine keeps isPlaying=true through buffering
        // gaps (the MediaSession flips to STATE_BUFFERING, isPlaying does
        // not). So when the phone shows PLAYING(3), isPlaying is true here
        // and — given DND active + no timer — the arm fires. The gate could
        // not have silently blocked the arm at PLAYING(3).
        assertTrue(shouldArmBedtimeSleep(dndActive = true, isPlaying = true, sleepTimerRunning = false))
    }

    // ── #1595 — shake listen window ────────────────────────────────

    private val fade = 10_000L

    @Test fun `does not listen when no timer is armed`() {
        assertFalse(shouldListenForShake(sleepTimerRemainingMs = null, shakeEnabled = true, fadeWindowMs = fade))
    }

    @Test fun `listens at the top of the fade tail (boundary)`() {
        assertTrue(shouldListenForShake(sleepTimerRemainingMs = 10_000L, shakeEnabled = true, fadeWindowMs = fade))
    }

    @Test fun `listens at the very end of the fade tail (boundary)`() {
        assertTrue(shouldListenForShake(sleepTimerRemainingMs = 0L, shakeEnabled = true, fadeWindowMs = fade))
    }

    @Test fun `does not listen just above the fade tail (still counting down)`() {
        assertFalse(shouldListenForShake(sleepTimerRemainingMs = 10_001L, shakeEnabled = true, fadeWindowMs = fade))
    }

    @Test fun `does not listen mid-countdown`() {
        assertFalse(shouldListenForShake(sleepTimerRemainingMs = 900_000L, shakeEnabled = true, fadeWindowMs = fade))
    }

    @Test fun `does not listen when the user disabled shake-to-extend`() {
        // Only reachable now that the engine-state clobber is fixed
        // (PlaybackState.withEngineUpdate): before the fix, shakeEnabled
        // was force-reset to true every ~50ms so this branch was dead.
        assertFalse(shouldListenForShake(sleepTimerRemainingMs = 5_000L, shakeEnabled = false, fadeWindowMs = fade))
    }

    // ── #1595 — shake extend gate ──────────────────────────────────

    @Test fun `extends when a shake fires inside the fade tail`() {
        assertTrue(shouldExtendOnShake(sleepTimerRemainingMs = 5_000L, fadeWindowMs = fade))
    }

    @Test fun `ignores a shake when no timer is running`() {
        assertFalse(shouldExtendOnShake(sleepTimerRemainingMs = null, fadeWindowMs = fade))
    }

    @Test fun `ignores a shake fired above the fade tail`() {
        assertFalse(shouldExtendOnShake(sleepTimerRemainingMs = 10_001L, fadeWindowMs = fade))
    }

    @Test fun `extends at the fade-tail end boundary`() {
        assertTrue(shouldExtendOnShake(sleepTimerRemainingMs = 0L, fadeWindowMs = fade))
    }

    // ── #1618 — post-stop shake-to-revive grace window ─────────────

    private val grace = 60_000L

    @Test fun `does not listen in grace window when the timer never fired`() {
        assertFalse(shouldListenInGraceWindow(msSinceFiredMs = null, graceWindowMs = grace))
    }

    @Test fun `listens right after the timer fires and partway through`() {
        assertTrue(shouldListenInGraceWindow(msSinceFiredMs = 0L, graceWindowMs = grace))
        assertTrue(shouldListenInGraceWindow(msSinceFiredMs = 30_000L, graceWindowMs = grace))
    }

    @Test fun `listens at the grace-window end boundary but not past it`() {
        assertTrue(shouldListenInGraceWindow(msSinceFiredMs = 60_000L, graceWindowMs = grace))
        assertFalse(shouldListenInGraceWindow(msSinceFiredMs = 60_001L, graceWindowMs = grace))
    }

    @Test fun `revives on a shake inside the window, ignores one past it or with no fire`() {
        assertTrue(shouldReviveOnShake(msSinceFiredMs = 0L, graceWindowMs = grace))
        assertTrue(shouldReviveOnShake(msSinceFiredMs = 60_000L, graceWindowMs = grace))
        assertFalse(shouldReviveOnShake(msSinceFiredMs = 60_001L, graceWindowMs = grace))
        assertFalse(shouldReviveOnShake(msSinceFiredMs = null, graceWindowMs = grace))
    }
}
