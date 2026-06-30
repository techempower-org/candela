package `in`.jphe.storyvox.playback.wear

import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.SentenceRange
import `in`.jphe.storyvox.playback.scrubProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the phone-side Wear publish cadence — [shouldPublishWearState]
 * and [positionMsToWearCharOffset]. The gate decides what the phone pushes to
 * `/playback/state`; the converter stamps the truthful position into each push.
 *
 * Extracted as top-level functions so the cadence + conversion can be exercised
 * without GMS `DataClient` (the seam pattern, mirroring `SeekPayload` and the
 * watch-side `decodeState`/`NodeSelection`).
 */
class WearBeaconTest {

    private val beacon = 1_000L
    private val playing = PlaybackState(
        currentChapterId = "c1",
        isPlaying = true,
        charOffset = 100,
        durationEstimateMs = 60_000L,
    )

    @Test
    fun `first push always publishes`() {
        assertTrue(shouldPublishWearState(null, playing, 0L, 0L, beacon))
    }

    @Test
    fun `a structural change publishes immediately regardless of timing`() {
        // Pause is a discrete event — must reach the wrist now, not on the next
        // beacon, even 1ms after the last push.
        val paused = playing.copy(isPlaying = false)
        assertTrue(shouldPublishWearState(playing, paused, 0L, 1L, beacon))
        // So are chapter/title/buffering/teleprompter changes.
        assertTrue(shouldPublishWearState(playing, playing.copy(isBuffering = true), 0L, 1L, beacon))
        assertTrue(shouldPublishWearState(playing, playing.copy(chapterTitle = "Ch 2"), 0L, 1L, beacon))
        assertTrue(shouldPublishWearState(playing, playing.copy(teleprompterPlaying = true), 0L, 1L, beacon))
    }

    @Test
    fun `position-only drift waits for the beacon interval`() {
        val moved = playing.copy(charOffset = 200)
        // Same structural state, < 1s since last push → hold.
        assertFalse(shouldPublishWearState(playing, moved, 0L, 500L, beacon))
        // ≥ 1s → beacon fires.
        assertTrue(shouldPublishWearState(playing, moved, 0L, 1_000L, beacon))
    }

    @Test
    fun `the heartbeat re-publishes an unchanged playing state once per interval`() {
        // Identical state (the ~1Hz ticker re-emitting the latest value): still
        // publishes once the interval elapses, so the watch keeps getting a fresh
        // position anchor + liveness proof.
        assertTrue(shouldPublishWearState(playing, playing, 0L, 1_000L, beacon))
        assertFalse(shouldPublishWearState(playing, playing, 0L, 999L, beacon))
    }

    @Test
    fun `a paused state never beacons no matter how much time passes`() {
        val paused = playing.copy(isPlaying = false)
        val pausedDrift = paused.copy(charOffset = 9_999)
        // No structural change + not playing ⇒ silence, even hours later. This is
        // what lets the watch read "no beacon while playing" as a drop without a
        // paused chapter false-tripping it.
        assertFalse(shouldPublishWearState(paused, pausedDrift, 0L, 3_600_000L, beacon))
        assertFalse(shouldPublishWearState(paused, paused, 0L, 3_600_000L, beacon))
    }

    @Test
    fun `the continuously-advancing position fields are masked from the structural compare`() {
        // charOffset, currentSentenceRange and sleepTimerRemainingMs all move
        // during normal playback; a change in any of them alone must NOT force an
        // off-beacon push (the watch extrapolates position itself).
        val drift = playing.copy(
            charOffset = 12_345,
            currentSentenceRange = SentenceRange(7, 100, 200),
            sleepTimerRemainingMs = 42_000L,
        )
        assertFalse(shouldPublishWearState(playing, drift, 0L, 100L, beacon))
    }

    @Test
    fun `positionMsToWearCharOffset is the speed-invariant rail conversion`() {
        assertEquals(0, positionMsToWearCharOffset(0L))
        // 8s * (150 wpm * 5 / 60) = 8 * 12.5 = 100 chars.
        assertEquals(100, positionMsToWearCharOffset(8_000L))
        // Negative (degenerate read) coerces to 0, never a negative offset.
        assertEquals(0, positionMsToWearCharOffset(-500L))
    }

    @Test
    fun `a stamped position reproduces the true progress fraction on the watch`() {
        // The point of stamping: the watch's scrubProgress over the stamped
        // charOffset must equal positionMs/duration, so the ring sits where the
        // audio actually is. 30s into a 60s chapter ⇒ exactly half.
        val stamped = playing.copy(charOffset = positionMsToWearCharOffset(30_000L))
        assertEquals(0.5f, stamped.scrubProgress(), 1e-4f)
    }
}
