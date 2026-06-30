package `in`.jphe.storyvox.wear.ongoing

import `in`.jphe.storyvox.playback.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wear companion — pins the pure [ongoingActionFor] rule that decides whether
 * the watch-face media chip ([WearOngoingPlaybackService]) should be shown for
 * a given phone [PlaybackState]. Tested without GMS, mirroring the
 * NodeSelection / WearStateDecode seam.
 */
class OngoingActionTest {

    @Test
    fun `shows the chip while playing a loaded chapter`() {
        val action = ongoingActionFor(
            PlaybackState(
                isPlaying = true,
                currentChapterId = "c1",
                chapterTitle = "Chapter 7",
                bookTitle = "The Brass Compendium",
            ),
        )
        assertTrue(action is OngoingAction.Show)
        action as OngoingAction.Show
        assertEquals("Chapter 7", action.title)
        assertEquals("The Brass Compendium", action.subtitle)
    }

    @Test
    fun `hides the chip when paused`() {
        assertEquals(
            OngoingAction.Hide,
            ongoingActionFor(
                PlaybackState(isPlaying = false, currentChapterId = "c1", chapterTitle = "Chapter 7"),
            ),
        )
    }

    @Test
    fun `hides the chip when no chapter is loaded, even if isPlaying is stale-true`() {
        // The last /playback/state DataItem persists across reboots; gating on a
        // loaded chapter keeps a stale isPlaying flag from resurrecting a chip.
        assertEquals(
            OngoingAction.Hide,
            ongoingActionFor(PlaybackState(isPlaying = true, currentChapterId = null)),
        )
    }

    @Test
    fun `title falls back to book then app name, and book is not duplicated as subtitle`() {
        val bookOnly = ongoingActionFor(
            PlaybackState(isPlaying = true, currentChapterId = "c1", bookTitle = "Just A Book"),
        ) as OngoingAction.Show
        assertEquals("Just A Book", bookOnly.title)
        // No chapter title → don't repeat the book as the subtitle.
        assertNull(bookOnly.subtitle)

        val nothing = ongoingActionFor(
            PlaybackState(isPlaying = true, currentChapterId = "c1"),
        ) as OngoingAction.Show
        assertEquals("storyvox", nothing.title)
        assertNull(nothing.subtitle)
    }
}
