package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #726 — buffering-stuck watchdog must mirror the user's
 * in-flight advance direction, not hardcode +1.
 *
 * Pre-fix the watchdog at [DefaultPlaybackController]:529 always
 * called `p.advanceChapter(direction = 1)` on a 1.5 s stuck-buffering
 * latch. When the user tapped Previous on a slow chapter, that
 * recovery skipped them forward by two chapters instead of landing
 * on the previous chapter once its body arrived — a non-deterministic
 * race violating the most basic media-player invariant ("Previous
 * never moves forward").
 *
 * The fix tracks `inFlightAdvanceDirection` on the EnginePlayer (set
 * on entry to advanceChapter, cleared in finally) and routes the
 * watchdog recovery through [DefaultPlaybackController.Companion.watchdogRecoveryDirection].
 * The full controller construction needs a Hilt graph + an
 * EnginePlayer with sherpa-onnx native AARs — same constraint as
 * [PlaybackControllerSkipSeekTest] — so the contract is tested via
 * the pure-function export.
 */
class WatchdogDirectionTest {

    @Test
    fun `mirrors positive in-flight direction`() {
        // User tapped Next (advanceChapter(+1) parked on body wait).
        // Watchdog fires at 1.5 s — must mirror forward so the
        // recovery lands on the same target the user originally
        // requested.
        assertEquals(1, DefaultPlaybackController.watchdogRecoveryDirection(1))
    }

    @Test
    fun `mirrors negative in-flight direction — the #726 case`() {
        // User tapped Previous (advanceChapter(-1) parked on body
        // wait while the previous chapter downloads). Pre-fix this
        // returned +1, turning Previous into a forward two-skip.
        // Post-fix it stays -1, preserving the "Previous never moves
        // forward" invariant.
        assertEquals(-1, DefaultPlaybackController.watchdogRecoveryDirection(-1))
    }

    @Test
    fun `falls back to plus one when no advance is in flight — original #553 case`() {
        // Engine stuck on isBuffering with no active advanceChapter
        // call (e.g. natural end-of-chapter never fired END_PILL
        // through the consumer on a Notion source — the original
        // #553 root cause). User was listening forward, so +1 is the
        // natural recovery direction.
        assertEquals(1, DefaultPlaybackController.watchdogRecoveryDirection(0))
    }

    @Test
    fun `normalizes stale positive values to plus one`() {
        // Defensive — earlier latch revisions could in principle
        // leak a non-{-1,0,+1} value into the field (e.g. if an
        // older `advanceChapter(direction = 5)` caller landed). The
        // resolver normalizes positive to +1 and negative to -1 so
        // the watchdog never passes an out-of-range value back into
        // `advanceChapter` (which already uses `if (direction >= 0)`
        // internally, but normalizing here keeps the contract tight).
        assertEquals(1, DefaultPlaybackController.watchdogRecoveryDirection(5))
    }

    @Test
    fun `normalizes stale negative values to minus one`() {
        assertEquals(-1, DefaultPlaybackController.watchdogRecoveryDirection(-7))
    }
}
