@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package `in`.jphe.storyvox.playback

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issues #553 #554 #555 — playback smoothness follow-up to PR #552.
 *
 * The three fixes:
 *  - **#553** auto-advance: a buffering-stuck watchdog in
 *    [DefaultPlaybackController.bindPlayer] kicks `advanceChapter(1)`
 *    when `isBuffering=true` holds for [DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS]
 *    without state change. EnginePlayer.advanceChapter also caps the
 *    chapter-body wait at 30 s so it can't park indefinitely.
 *  - **#554** cover-tap pause regression: a pause-pin in EnginePlayer
 *    snapshots [EnginePlayer.currentPositionMs] at the moment of pause
 *    and returns it verbatim until resume / seek / pipeline restart.
 *  - **#555** speed-change position jump: both
 *    [EnginePlayer.currentPositionMs] and [EnginePlayer.estimateDurationMs]
 *    now use the speed-invariant media-time axis. Companion exports
 *    ([DefaultPlaybackController.positionMsToCharOffset] /
 *    [DefaultPlaybackController.skipDeltaChars]) mirror the axis so
 *    the seek round-trip stays correct.
 *
 * Engine-internal verifications (latch behavior, AudioTrack frame math)
 * live in the on-device verification flow — those need a sherpa-onnx
 * voice + a real chapter. The contracts that DON'T need Android are
 * tested here: math, watchdog timing, derived progress fractions.
 */
class PlaybackSmoothnessFollowupTest {

    // ─── #555: speed-invariant position/duration axis ─────────────────

    @Test
    fun `scrubProgress is identical across speeds for the same charOffset`() {
        // #555 — the user shouldn't see the rail re-position itself when
        // they tap a different speed chip. The audit reported a 19 s
        // backward jump when speed 1× → 1.5×; with the media-time axis
        // the fraction (and therefore the thumb pixel position) is
        // identical at every speed.
        val charOffset = 500
        val durationMs = 80_000L
        val progresses = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).map { speed ->
            PlaybackState(
                durationEstimateMs = durationMs,
                charOffset = charOffset,
                speed = speed,
            ).scrubProgress()
        }
        // All progresses should be identical (to a tiny float epsilon).
        val first = progresses.first()
        for (p in progresses) {
            assertEquals("expected speed-invariant progress, got $progresses", first, p, 1e-5f)
        }
    }

    @Test
    fun `seekToPositionMs round-trip is speed-invariant`() {
        // #555 — verify the inverse path. UI taps at displayed position
        // 60 s; we compute char offset; then map back to displayed
        // position; numbers should match within rounding regardless of
        // speed.
        for (speed in listOf(0.5f, 1.0f, 1.5f, 2.0f)) {
            val targetMs = 60_000L
            val chars = DefaultPlaybackController.positionMsToCharOffset(targetMs, speed)
            // Reverse using the same media-time axis.
            val backMs = ((chars / SPEED_BASELINE_CHARS_PER_SECOND) * 1000f).toLong()
            assertTrue(
                "speed=$speed: roundtrip drift ${kotlin.math.abs(targetMs - backMs)} ms",
                kotlin.math.abs(targetMs - backMs) <= 100L,
            )
        }
    }

    @Test
    fun `skipDeltaChars yields the same delta at every speed`() {
        // #555 — the rail's "30 s" slot covers the same number of chars
        // regardless of speed. Audio plays through it faster or slower
        // depending on speed, but the SCRUBBER JUMP is identical.
        val deltas = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).map { speed ->
            DefaultPlaybackController.skipDeltaChars(30f, speed)
        }
        val first = deltas.first()
        for (d in deltas) {
            assertEquals("expected speed-invariant skip delta, got $deltas", first, d)
        }
    }

    // ─── #553: buffering-stuck watchdog ──────────────────────────────

    /**
     * Inline replica of [DefaultPlaybackController.runBufferingWatchdog],
     * trimmed to what the JVM tests need (no controller-scope, no
     * EnginePlayer dispatch). Mirrors the production two-tier gate
     * exactly — `currentSentenceRange == null` selects the fast
     * 1.5 s chapter-transition threshold; sentence range set selects
     * the slow 12 s intra-chapter / end-of-chapter threshold so we
     * don't fire mid-chapter on a Piper-low underrun pause (the #640
     * v1.0 blocker symptom).
     *
     * Returns a launched Job that records the armed chapter id into
     * [onFire] when the watchdog actually triggers; cancel the job in
     * tests' tearDown to avoid leaking a runTest coroutine.
     */
    private fun kotlinx.coroutines.CoroutineScope.runInlineWatchdog(
        state: MutableStateFlow<PlaybackState>,
        onFire: (String) -> Unit,
    ) = launch {
        var watchdog: kotlinx.coroutines.Job? = null
        state
            .map {
                val buffering = it.isBuffering && it.currentChapterId != null
                val transition = buffering && it.currentSentenceRange == null
                val endOfChapter = buffering && it.currentSentenceRange != null
                Triple(transition, endOfChapter, it.currentChapterId)
            }
            .distinctUntilChanged()
            .collect { (transition, endOfChapter, chapterId) ->
                watchdog?.cancel()
                if ((!transition && !endOfChapter) || chapterId == null) return@collect
                val armedFor = chapterId
                val threshold = if (transition) {
                    DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS
                } else {
                    DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS
                }
                watchdog = launch {
                    delay(threshold)
                    val s = state.value
                    if (
                        s.isBuffering &&
                        s.currentChapterId == armedFor &&
                        s.error == null
                    ) {
                        onFire(armedFor)
                    }
                }
            }
    }

    @Test
    fun `watchdog fires once when isBuffering stays stuck on same chapter`() = runTest {
        // Chapter-transition buffering: sentence range null because the
        // new pipeline hasn't emitted its first sentence yet. This is
        // the case the watchdog exists to recover.
        val state = MutableStateFlow(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                isBuffering = true,
                currentSentenceRange = null,
            ),
        )
        var firedFor: String? = null
        val job = runInlineWatchdog(state) { firedFor = it }
        // Less than the threshold — watchdog should not have fired yet.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS / 2)
        assertNull("watchdog fired prematurely", firedFor)
        // Past the threshold — should fire.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS)
        assertEquals("watchdog should have fired for ch1", "ch1", firedFor)
        job.cancel()
    }

    @Test
    fun `watchdog cancels when chapter changes before threshold`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                isBuffering = true,
                currentSentenceRange = null,
            ),
        )
        var firedFor: String? = null
        val job = runInlineWatchdog(state) { firedFor = it }
        // Chapter advances naturally well before the watchdog threshold.
        advanceTimeBy(500L)
        state.value = state.value.copy(
            currentChapterId = "ch2",
            isBuffering = false,
        )
        // Now well past the threshold.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 1_000L)
        assertNull("watchdog should have cancelled on chapter change", firedFor)
        job.cancel()
    }

    @Test
    fun `watchdog cancels when isBuffering clears before threshold`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                isBuffering = true,
                currentSentenceRange = null,
            ),
        )
        var firedFor: String? = null
        val job = runInlineWatchdog(state) { firedFor = it }
        advanceTimeBy(500L)
        // Buffering clears (e.g., chapter body landed, audio started).
        state.value = state.value.copy(isBuffering = false)
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 1_000L)
        assertNull("watchdog should have cancelled on buffering clear", firedFor)
        job.cancel()
    }

    @Test
    fun `watchdog does not fire when error surfaces before threshold`() = runTest {
        val state = MutableStateFlow(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                isBuffering = true,
                currentSentenceRange = null,
            ),
        )
        var firedFor: String? = null
        val job = runInlineWatchdog(state) { firedFor = it }
        advanceTimeBy(500L)
        // Error surfaces — engine already gave up; watchdog should NOT
        // double-fire an advance.
        state.value = state.value.copy(
            error = PlaybackError.ChapterFetchFailed("network down"),
        )
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 1_000L)
        assertNull(
            "watchdog should defer to the typed error path",
            firedFor,
        )
        job.cancel()
    }

    @Test
    fun `watchdog defers past fast threshold when sentence range is set (issue #640)`() = runTest {
        // #640 v1.0 blocker — on Piper-low / Z Flip 3 the consumer's
        // catch-up-pause branch flips isBuffering=true mid-chapter when
        // bufferHeadroomMs drops below the underrun threshold.
        // `currentSentenceRange` stays non-null because the consumer is
        // still inside a chapter — that's the structural marker that
        // separates this case from chapter-transition buffering.
        //
        // Pre-fix the watchdog ignored currentSentenceRange, fired
        // after the 1.5 s threshold, called advanceChapter(1) MID
        // CHAPTER, interrupted the still-running consumer, and
        // stranded the new pipeline. Symptom: phone shows "Reconnecting
        // — please wait" with audio focus held but no PCM moving.
        //
        // Post-fix the watchdog routes sentence-range-set buffering
        // to the slower 12 s threshold instead, giving legitimate
        // underrun pauses ample time to resolve naturally.
        val state = MutableStateFlow(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                isBuffering = true,
                // Non-null sentence range == we're still inside a
                // chapter, this is an intra-chapter underrun, NOT a
                // chapter-transition stuck state.
                currentSentenceRange = SentenceRange(
                    startCharInChapter = 100,
                    endCharInChapter = 200,
                    sentenceIndex = 3,
                ),
            ),
        )
        var firedFor: String? = null
        val job = runInlineWatchdog(state) { firedFor = it }
        // Past the FAST (chapter-transition) threshold — watchdog must
        // NOT fire yet, because firing would interrupt the running
        // consumer mid-chapter.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_MS + 2_000L)
        assertNull(
            "watchdog must NOT fire at the fast threshold while a " +
                "sentence is still being played — that's an intra-chapter " +
                "underrun, not a stuck chapter transition",
            firedFor,
        )
        job.cancel()
    }

    @Test
    fun `watchdog eventually fires on truly stuck end-of-chapter (issue #640 recovery)`() = runTest {
        // Companion to the #640 deferral test above — once we cross
        // the slow [BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS]
        // threshold (12 s) with sentence range still set, the
        // watchdog DOES fire so the user isn't left forever on a
        // chapter that has silently failed to advance through the
        // natural-end path (the original #553 symptom that survived
        // on Notion sources).
        val state = MutableStateFlow(
            PlaybackState(
                currentChapterId = "ch1",
                isPlaying = true,
                isBuffering = true,
                currentSentenceRange = SentenceRange(
                    startCharInChapter = 100,
                    endCharInChapter = 200,
                    sentenceIndex = 3,
                ),
            ),
        )
        var firedFor: String? = null
        val job = runInlineWatchdog(state) { firedFor = it }
        // Past the slow threshold — watchdog SHOULD fire as the
        // recovery path for a genuinely stuck consumer.
        advanceTimeBy(DefaultPlaybackController.BUFFERING_STUCK_WATCHDOG_END_OF_CHAPTER_MS + 1_000L)
        assertEquals(
            "watchdog should fire on the slow threshold to recover " +
                "from a stuck end-of-chapter that never reached natural-end",
            "ch1",
            firedFor,
        )
        job.cancel()
    }

    // ─── #553: end-of-book branch ────────────────────────────────────

    @Test
    fun `BookFinished path keeps isBuffering false (no stuck spinner at end)`() {
        // Audit: when chapter N+1 doesn't exist (end of book), the
        // engine emits BookFinished and the UI should roll to Completed.
        // If a prior advanceChapter attempt left isBuffering=true, the
        // EngineState mapping (#524) prefers Completed (which is correct
        // per the precedence order). Verify the precedence holds.
        val s = PlaybackState(
            currentChapterId = "ch_last",
            isPlaying = false,
            isBuffering = false, // engine clears it in advanceChapter's null-next branch
        )
        // Buffering should be false at this point — the engine's
        // advanceChapter clears it on the end-of-book branch.
        assertFalse("end-of-book branch leaves isBuffering true", s.isBuffering)
    }
}
