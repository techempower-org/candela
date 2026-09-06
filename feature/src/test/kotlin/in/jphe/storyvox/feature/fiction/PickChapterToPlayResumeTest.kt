package `in`.jphe.storyvox.feature.fiction

import `in`.jphe.storyvox.feature.api.UiChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1685 — "Resume goes to the last chapter ever listened to instead of
 * the one listened to most recently."
 *
 * Pre-fix [pickChapterToPlay] returned the first non-finished chapter — a
 * *progress frontier*. A listener who finished chapters 1–50, then went back to
 * re-listen to chapter 10, was bounced to chapter 51 because chapter 10 was
 * already marked played and nothing about the frontier ever moves backwards.
 *
 * Post-fix the picker takes the fiction's most-recently-updated saved
 * position's chapter (`resumeChapterId`, `ORDER BY updatedAt DESC LIMIT 1` in
 * the DAO) and returns it when present in the list, finished or not. The
 * frontier rule remains the fallback when there is no saved position — so the
 * #604 contract in [FictionDetailPlayButtonTest] still holds verbatim.
 */
class PickChapterToPlayResumeTest {

    private fun ch(id: String, number: Int, finished: Boolean) = UiChapter(
        id = id,
        number = number,
        title = "Chapter $number",
        publishedRelative = "1d",
        durationLabel = "12 min",
        isDownloaded = false,
        isFinished = finished,
    )

    /** The exact #1685 scenario: everything through c5 finished, most recent
     *  position is back in c2. Resume must land in c2, not c6. */
    @Test
    fun `re-listening to an earlier finished chapter resumes there, not at the frontier`() {
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
            ch("c3", 3, finished = true),
            ch("c4", 4, finished = true),
            ch("c5", 5, finished = true),
            ch("c6", 6, finished = false),
        )
        assertEquals("c2", pickChapterToPlay(chapters, resumeChapterId = "c2")?.id)
        // And the frontier rule is what the OLD code would have done:
        assertEquals("c6", pickChapterToPlay(chapters, resumeChapterId = null)?.id)
    }

    @Test
    fun `most recent position on an unfinished chapter resumes there`() {
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = false),
            ch("c3", 3, finished = false),
        )
        // User skipped ahead to c3 and stopped mid-way; c2 is the frontier.
        assertEquals("c3", pickChapterToPlay(chapters, resumeChapterId = "c3")?.id)
    }

    @Test
    fun `no saved position falls back to the first unfinished chapter`() {
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = false),
        )
        assertEquals("c2", pickChapterToPlay(chapters, resumeChapterId = null)?.id)
    }

    @Test
    fun `saved position for a chapter no longer in the list falls back to the frontier`() {
        // Source pruned the chapter, or the id scheme changed — never return a
        // chapter that isn't rendered; degrade to the #604 rule.
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = false),
        )
        assertEquals("c2", pickChapterToPlay(chapters, resumeChapterId = "gone")?.id)
    }

    @Test
    fun `fully finished book with a most recent position resumes that chapter (not chapter 1)`() {
        // Re-listening to a finished book: the listener was in c2 last time,
        // so Resume goes back into c2 rather than restarting at c1.
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
            ch("c3", 3, finished = true),
        )
        assertEquals("c2", pickChapterToPlay(chapters, resumeChapterId = "c2")?.id)
    }

    @Test
    fun `empty chapter list is null regardless of a saved position`() {
        assertNull(pickChapterToPlay(emptyList(), resumeChapterId = "c1"))
    }

    // ── composed contracts ────────────────────────────────────────────────

    @Test
    fun `open-scroll anchor follows the most recent chapter too (#1676 composed)`() {
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
            ch("c3", 3, finished = false),
        )
        val picked = pickChapterToPlay(chapters, resumeChapterId = "c1")
        assertEquals(0, targetScrollIndex(chapters, picked?.id))
    }

    @Test
    fun `label reads Play when the most recent chapter is the first one`() {
        val chapters = listOf(
            ch("c1", 1, finished = false),
            ch("c2", 2, finished = false),
        )
        val picked = pickChapterToPlay(chapters, resumeChapterId = "c1")
        assertEquals("Play", playButtonLabel(chapters, picked))
    }

    @Test
    fun `label reads Resume when the most recent chapter is not the first`() {
        val chapters = listOf(
            ch("c1", 1, finished = true),
            ch("c2", 2, finished = true),
            ch("c3", 3, finished = false),
        )
        val picked = pickChapterToPlay(chapters, resumeChapterId = "c2")
        assertEquals("Resume", playButtonLabel(chapters, picked))
    }
}
