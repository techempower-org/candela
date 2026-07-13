package `in`.jphe.storyvox.feature.fiction

import `in`.jphe.storyvox.feature.api.UiChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1676 — the chapter list should open scrolled to the resume chapter so
 * a listener returning to a book lands where they left off. [targetScrollIndex]
 * is the pure decision the once-only `LaunchedEffect` in `FictionDetailScreen`
 * calls (the Compose scroll itself is device-verified; this is the CI-provable
 * core). `resumeId` is `pickChapterToPlay(chapters)?.id`, so the composed
 * behavior is pinned here too.
 */
class TargetScrollIndexTest {

    private fun ch(id: String, number: Int, finished: Boolean) = UiChapter(
        id = id,
        number = number,
        title = "Chapter $number",
        publishedRelative = "1d",
        durationLabel = "12 min",
        isDownloaded = false,
        isFinished = finished,
    )

    // ── targetScrollIndex (pure) ──────────────────────────────────────────

    @Test
    fun `null resumeId means no scroll`() {
        assertNull(targetScrollIndex(listOf(ch("a", 1, false)), resumeId = null))
    }

    @Test
    fun `empty chapter list means no scroll`() {
        assertNull(targetScrollIndex(emptyList(), resumeId = "a"))
    }

    @Test
    fun `resume id absent from the list means no scroll (defensive)`() {
        assertNull(targetScrollIndex(listOf(ch("a", 1, false), ch("b", 2, false)), resumeId = "zzz"))
    }

    @Test
    fun `present resume id returns its index`() {
        val chapters = listOf(ch("a", 1, true), ch("b", 2, true), ch("c", 3, false))
        assertEquals(2, targetScrollIndex(chapters, resumeId = "c"))
    }

    // ── composed with pickChapterToPlay (the real resume anchor) ──────────

    @Test
    fun `fresh fiction resumes at the first chapter (index 0)`() {
        val chapters = listOf(ch("a", 1, false), ch("b", 2, false))
        assertEquals(0, targetScrollIndex(chapters, pickChapterToPlay(chapters)?.id))
    }

    @Test
    fun `partway through resumes at the first unfinished chapter`() {
        val chapters = listOf(ch("a", 1, true), ch("b", 2, true), ch("c", 3, false))
        assertEquals(2, targetScrollIndex(chapters, pickChapterToPlay(chapters)?.id))
    }

    @Test
    fun `fully finished fiction falls back to the first chapter`() {
        val chapters = listOf(ch("a", 1, true), ch("b", 2, true))
        assertEquals(0, targetScrollIndex(chapters, pickChapterToPlay(chapters)?.id))
    }

    @Test
    fun `empty fiction never scrolls`() {
        val chapters = emptyList<UiChapter>()
        assertNull(targetScrollIndex(chapters, pickChapterToPlay(chapters)?.id))
    }
}
