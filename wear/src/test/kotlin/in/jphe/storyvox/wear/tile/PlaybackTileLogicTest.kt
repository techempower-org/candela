package `in`.jphe.storyvox.wear.tile

import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the [PlaybackTileService] render helpers — the state→display
 * decisions, exercised without standing up a TileService or GMS (the seam
 * pattern, mirroring SeekPayloadTest / WearStateDecodeTest).
 */
class PlaybackTileLogicTest {

    @Test fun `play command issued when paused`() {
        assertEquals(PhoneWearBridge.CMD_PLAY, playPauseCommand(PlaybackState(isPlaying = false)))
    }

    @Test fun `pause command issued when playing`() {
        assertEquals(PhoneWearBridge.CMD_PAUSE, playPauseCommand(PlaybackState(isPlaying = true)))
    }

    @Test fun `book line falls back to empty-state copy`() {
        assertEquals("Nothing playing", bookLine(PlaybackState()))
        assertEquals("Nothing playing", bookLine(PlaybackState(bookTitle = "   ")))
    }

    @Test fun `book line uses the title when present`() {
        assertEquals("Dune", bookLine(PlaybackState(bookTitle = "Dune")))
    }

    @Test fun `chapter line is null when absent or blank`() {
        assertNull(chapterLine(PlaybackState()))
        assertNull(chapterLine(PlaybackState(chapterTitle = " ")))
    }

    @Test fun `chapter line uses the title when present`() {
        assertEquals("Chapter 3", chapterLine(PlaybackState(chapterTitle = "Chapter 3")))
    }

    @Test fun `progress percent tracks the scrubber math`() {
        // scrubProgress: totalChars = (durationMs/1000) * 12.5 baseline cps.
        // 600_000ms → 7500 chars; offset 3750 → 0.5 → 50%.
        val half = PlaybackState(charOffset = 3750, durationEstimateMs = 600_000L)
        assertEquals(50, progressPercent(half))
    }

    @Test fun `progress percent is zero when duration is unknown`() {
        // Before the first state sync durationEstimateMs == 0; must not divide by
        // a zero rail.
        assertEquals(0, progressPercent(PlaybackState(charOffset = 100, durationEstimateMs = 0L)))
    }
}
