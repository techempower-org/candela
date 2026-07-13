package `in`.jphe.storyvox.feature.fiction

import `in`.jphe.storyvox.feature.api.UiChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1676 (live auto-follow half) — the chapter list should live-follow the
 * *playing* chapter as playback advances, suspend when the user scrolls away,
 * and offer a re-engage chip. [followScrollIndex] (should-we-scroll-and-where)
 * and [followChipDirection] (should-the-chip-show-and-which-way) are the pure
 * decisions the Compose effects call — the animate-scroll itself is
 * device-verified; these are the CI-provable core (mirrors [TargetScrollIndexTest]).
 */
class ChapterAutoFollowTest {

    private fun ch(id: String, number: Int) = UiChapter(
        id = id,
        number = number,
        title = "Chapter $number",
        publishedRelative = "1d",
        durationLabel = "12 min",
        isDownloaded = false,
        isFinished = false,
    )

    /** A list of [n] chapters with ids "c0"…"c(n-1)". */
    private fun chapters(n: Int) = (0 until n).map { ch("c$it", it + 1) }

    // ── followScrollIndex: the follow decision ────────────────────────────

    @Test
    fun `follows forward when the playing chapter advances below the viewport`() {
        // Playing "c5" (index 5) → list-item 6 with headerOffset 1. Visible rows
        // are items 1..4 (chapters above it), so it's off-screen below → scroll.
        val target = followScrollIndex(
            chapters = chapters(10),
            playingChapterId = "c5",
            headerOffset = 1,
            firstVisibleItemIndex = 1,
            lastVisibleItemIndex = 4,
            suspended = false,
        )
        assertEquals(6, target)
    }

    @Test
    fun `follows backward when the playing chapter is above the viewport`() {
        // Playing "c5" → item 6; viewport is items 8..11 (scrolled past it) → scroll up.
        val target = followScrollIndex(
            chapters = chapters(20),
            playingChapterId = "c5",
            headerOffset = 1,
            firstVisibleItemIndex = 8,
            lastVisibleItemIndex = 11,
            suspended = false,
        )
        assertEquals(6, target)
    }

    @Test
    fun `no-op when the playing chapter is already comfortably visible`() {
        // Item 6 sits well inside the visible range [1..11] (margin 1) → don't jitter.
        assertNull(
            followScrollIndex(
                chapters = chapters(20),
                playingChapterId = "c5",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 11,
                suspended = false,
            ),
        )
    }

    @Test
    fun `re-centres when the playing chapter is hugging the top edge`() {
        // Item 6 == firstVisibleItemIndex: technically visible but glued to the
        // edge; the edge margin re-centres it so it isn't clipped at the top.
        val target = followScrollIndex(
            chapters = chapters(20),
            playingChapterId = "c5",
            headerOffset = 1,
            firstVisibleItemIndex = 6,
            lastVisibleItemIndex = 15,
            suspended = false,
        )
        assertEquals(6, target)
    }

    @Test
    fun `suspended follow never scrolls even when the playing chapter is off-screen`() {
        assertNull(
            followScrollIndex(
                chapters = chapters(10),
                playingChapterId = "c5",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = true,
            ),
        )
    }

    @Test
    fun `no playing chapter means no follow`() {
        assertNull(
            followScrollIndex(
                chapters = chapters(10),
                playingChapterId = null,
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = false,
            ),
        )
    }

    @Test
    fun `playing id absent from the list means no follow (defensive, e g filtered out)`() {
        assertNull(
            followScrollIndex(
                chapters = chapters(10),
                playingChapterId = "not-in-list",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = false,
            ),
        )
    }

    @Test
    fun `header offset is added to the chapter index (narrow-layout preamble)`() {
        // Narrow layout: 6 header items above the chapter rows. Playing the first
        // chapter ("c0", index 0) → list-item 6, off-screen below the header → scroll.
        val target = followScrollIndex(
            chapters = chapters(10),
            playingChapterId = "c0",
            headerOffset = 6,
            firstVisibleItemIndex = 0,
            lastVisibleItemIndex = 3,
            suspended = false,
        )
        assertEquals(6, target)
    }

    // ── followChipDirection: the re-engage chip decision ──────────────────

    @Test
    fun `chip points down when suspended and the playing chapter is below the viewport`() {
        assertEquals(
            FollowChipDirection.Down,
            followChipDirection(
                chapters = chapters(10),
                playingChapterId = "c5",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = true,
            ),
        )
    }

    @Test
    fun `chip points up when suspended and the playing chapter is above the viewport`() {
        assertEquals(
            FollowChipDirection.Up,
            followChipDirection(
                chapters = chapters(20),
                playingChapterId = "c5",
                headerOffset = 1,
                firstVisibleItemIndex = 8,
                lastVisibleItemIndex = 11,
                suspended = true,
            ),
        )
    }

    @Test
    fun `no chip while suspended if the playing chapter is on-screen`() {
        // Tapping to scroll to a row you can already see would be pointless.
        assertNull(
            followChipDirection(
                chapters = chapters(20),
                playingChapterId = "c5",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 11,
                suspended = true,
            ),
        )
    }

    @Test
    fun `no chip while auto-follow is active (not suspended)`() {
        // When following, the list keeps the row in view itself — no chip needed.
        assertNull(
            followChipDirection(
                chapters = chapters(10),
                playingChapterId = "c5",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = false,
            ),
        )
    }

    @Test
    fun `no chip when nothing from this fiction is playing`() {
        assertNull(
            followChipDirection(
                chapters = chapters(10),
                playingChapterId = null,
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = true,
            ),
        )
    }

    @Test
    fun `no chip when the playing id is absent from the rendered list`() {
        assertNull(
            followChipDirection(
                chapters = chapters(10),
                playingChapterId = "not-in-list",
                headerOffset = 1,
                firstVisibleItemIndex = 1,
                lastVisibleItemIndex = 4,
                suspended = true,
            ),
        )
    }
}
