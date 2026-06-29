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

    // ---- Issue #1262 — shouldWatchdogFireFallbackAdvance ----
    //
    // The buffering-stuck watchdog must not skip a chapter that is merely
    // warming up its first synth chunk. A never-finalized "hourglass"
    // (partial PCM cache) chapter is always a cache MISS, so its first-chunk
    // warm-up always overran the 1.5 s "transition" threshold and the
    // pre-#1262 watchdog advanced PAST it on every auto-advance — yet a
    // manual tap (warm/foreground) beat the timer and played fine. The
    // resolver refuses to fire for exactly that warm-up state.

    @Test
    fun `does NOT fire during first-chunk warm-up — the #1262 case`() {
        // Buffering, on the armed chapter, no error, NO sentence emitted
        // yet, and NO advance in flight (advanceChapter already returned and
        // cleared its latch, so loadAndPlay HAS started the pipeline). This
        // is the post-load synth warm-up of a cache-MISS chapter — skipping
        // it is the #1262 bug.
        assertEquals(
            false,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = true,
                currentChapterIdMatchesArmed = true,
                hasError = false,
                noActiveSentenceRange = true,
                inFlightAdvanceDirection = 0,
            ),
        )
    }

    @Test
    fun `fires when an advance is parked on the body-wait — forward`() {
        // No sentence emitted yet, but an advance IS in flight (+1): the
        // next chapter's body genuinely hasn't landed. Fire (the engine-side
        // mutex then dedups it if the body lands first; advanceChapter's own
        // 60 s timeout is the hard backstop).
        assertEquals(
            true,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = true,
                currentChapterIdMatchesArmed = true,
                hasError = false,
                noActiveSentenceRange = true,
                inFlightAdvanceDirection = 1,
            ),
        )
    }

    @Test
    fun `fires when a Previous advance is parked on the body-wait`() {
        // Same as above but the user tapped Previous (-1). Fire — the
        // direction resolver keeps it going backward (#726).
        assertEquals(
            true,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = true,
                currentChapterIdMatchesArmed = true,
                hasError = false,
                noActiveSentenceRange = true,
                inFlightAdvanceDirection = -1,
            ),
        )
    }

    @Test
    fun `fires on an end-of-chapter stall with no advance in flight — original #553`() {
        // A sentence WAS emitted (noActiveSentenceRange == false): the
        // chapter played to its end but the natural-end advance never fired
        // (the original #553 Notion symptom). Fire even with no advance in
        // flight — this is genuinely stuck, not a warm-up.
        assertEquals(
            true,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = true,
                currentChapterIdMatchesArmed = true,
                hasError = false,
                noActiveSentenceRange = false,
                inFlightAdvanceDirection = 0,
            ),
        )
    }

    @Test
    fun `does not fire when no longer buffering`() {
        assertEquals(
            false,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = false,
                currentChapterIdMatchesArmed = true,
                hasError = false,
                noActiveSentenceRange = true,
                inFlightAdvanceDirection = 0,
            ),
        )
    }

    @Test
    fun `does not fire when the chapter moved off the armed id`() {
        // A clean transition happened between arm and fire — don't skip the
        // chapter we just landed on.
        assertEquals(
            false,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = true,
                currentChapterIdMatchesArmed = false,
                hasError = false,
                noActiveSentenceRange = true,
                inFlightAdvanceDirection = 1,
            ),
        )
    }

    @Test
    fun `does not fire when an error is already surfaced`() {
        // The engine surfaced a typed error (e.g. body-wait timeout); the UI
        // shows it. Don't pile a skip on top.
        assertEquals(
            false,
            DefaultPlaybackController.shouldWatchdogFireFallbackAdvance(
                isBuffering = true,
                currentChapterIdMatchesArmed = true,
                hasError = true,
                noActiveSentenceRange = false,
                inFlightAdvanceDirection = 1,
            ),
        )
    }
}
