package `in`.jphe.storyvox.wear.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [isWearStateStale] — the "phone went quiet mid-listen" rule
 * that drives the NowPlaying "Reconnecting…" hint.
 *
 * Extracted as a top-level function so it's testable without GMS (the seam
 * pattern, mirroring [WearPlaybackBridge.decodeState] and `NodeSelection`).
 */
class WearStalenessTest {

    private val threshold = 5_000L

    @Test
    fun `a paused or idle state is never stale`() {
        // The phone deliberately stops beaconing while paused, so a growing gap
        // is expected, not a fault — must not read as "Reconnecting…".
        assertFalse(isWearStateStale(isPlaying = false, elapsedSinceBeaconMs = 999_999L, staleThresholdMs = threshold))
    }

    @Test
    fun `playing with recent beacons is fresh`() {
        assertFalse(isWearStateStale(isPlaying = true, elapsedSinceBeaconMs = 4_999L, staleThresholdMs = threshold))
    }

    @Test
    fun `playing with no beacon past the threshold is stale`() {
        assertTrue(isWearStateStale(isPlaying = true, elapsedSinceBeaconMs = 5_000L, staleThresholdMs = threshold))
        assertTrue(isWearStateStale(isPlaying = true, elapsedSinceBeaconMs = 20_000L, staleThresholdMs = threshold))
    }
}
