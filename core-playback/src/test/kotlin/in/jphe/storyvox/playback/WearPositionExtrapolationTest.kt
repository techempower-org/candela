package `in`.jphe.storyvox.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [extrapolatedScrubProgress] — the watch-side position
 * extrapolation that keeps the Wear scrubber smooth between the phone's ~1Hz
 * beacons (the char-axis analog of [in.jphe.storyvox.playback.tts.extrapolateFrames]).
 *
 * The rail is speed-invariant (see [scrubProgress]); position is consumed at
 * [SPEED_BASELINE_CHARS_PER_SECOND] `*` speed chars/sec in wall-clock, so the
 * progress fraction moves faster at higher speed (book ends sooner) — exactly
 * what these tests pin.
 *
 * 150 wpm baseline ⇒ 12.5 chars/sec; a 60 000 ms chapter has a 750-char rail.
 */
class WearPositionExtrapolationTest {

    /** charOffset 0, 60s chapter, 1x, playing — the canonical anchor. */
    private val playing = PlaybackState(
        durationEstimateMs = 60_000L,
        charOffset = 0,
        isPlaying = true,
        speed = 1.0f,
    )

    @Test
    fun `playing advances the fraction proportionally to elapsed time`() {
        // 1s elapsed ⇒ 12.5 chars ⇒ 12.5 / 750 rail.
        assertEquals(12.5f / 750f, playing.extrapolatedScrubProgress(1_000L), 1e-4f)
        // 2s ⇒ double.
        assertEquals(25f / 750f, playing.extrapolatedScrubProgress(2_000L), 1e-4f)
    }

    @Test
    fun `higher speed advances the fraction faster`() {
        // At 2x, 1s of wall-clock consumes 25 chars, not 12.5 — the rail is
        // speed-invariant, so the fraction moves twice as fast.
        val fast = playing.copy(speed = 2.0f)
        assertEquals(25f / 750f, fast.extrapolatedScrubProgress(1_000L), 1e-4f)
    }

    @Test
    fun `it builds on the anchor charOffset, not from zero`() {
        // Anchored mid-chapter at the 0.5 mark (375 chars), +1s ⇒ 387.5 / 750.
        val mid = playing.copy(charOffset = 375)
        assertEquals(387.5f / 750f, mid.extrapolatedScrubProgress(1_000L), 1e-4f)
    }

    @Test
    fun `a paused state holds position regardless of elapsed time`() {
        val paused = playing.copy(charOffset = 375, isPlaying = false)
        // Returns the static scrubProgress (0.5) — no advance while paused.
        assertEquals(0.5f, paused.extrapolatedScrubProgress(10_000L), 1e-4f)
        assertEquals(paused.scrubProgress(), paused.extrapolatedScrubProgress(10_000L), 1e-6f)
    }

    @Test
    fun `a buffering state holds position`() {
        // Audio isn't draining during an underrun, so don't extrapolate past it.
        val buffering = playing.copy(charOffset = 375, isBuffering = true)
        assertEquals(0.5f, buffering.extrapolatedScrubProgress(10_000L), 1e-4f)
    }

    @Test
    fun `non-positive elapsed returns the static fraction`() {
        val mid = playing.copy(charOffset = 375)
        assertEquals(0.5f, mid.extrapolatedScrubProgress(0L), 1e-4f)
        assertEquals(0.5f, mid.extrapolatedScrubProgress(-1_000L), 1e-4f)
    }

    @Test
    fun `extrapolation clamps at the end of the rail`() {
        // Near the end + a long gap would overshoot; the fraction never exceeds 1.
        val nearEnd = playing.copy(charOffset = 740)
        assertEquals(1.0f, nearEnd.extrapolatedScrubProgress(10_000L), 1e-6f)
    }

    @Test
    fun `unknown duration yields zero`() {
        val noDuration = playing.copy(durationEstimateMs = 0L)
        assertEquals(0f, noDuration.extrapolatedScrubProgress(1_000L), 1e-6f)
    }
}
