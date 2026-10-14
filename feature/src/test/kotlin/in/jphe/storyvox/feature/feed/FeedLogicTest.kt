package `in`.jphe.storyvox.feature.feed

import `in`.jphe.storyvox.data.repository.playback.UnreadChapter
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedLogicTest {

    private fun unread(fid: String, cid: String, book: String = "Book $fid", chapter: String = "Ch $cid") =
        UnreadChapter(fictionId = fid, chapterId = cid, bookTitle = book, chapterTitle = chapter, coverUrl = null)

    private val sourceOf: (String) -> String = { it.substringBefore(':') }
    private val labels = mapOf("rss" to "RSS", "hackernews" to "Hacker News")

    @Test
    fun `rows keep reverse-chron order and label sources`() {
        val rows = FeedLogic.rows(
            unread = listOf(unread("rss:a", "c1"), unread("hackernews:b", "c2"), unread("rss:a", "c3")),
            sourceIdOf = sourceOf,
            labelOf = labels::get,
        )
        assertEquals(listOf("c1", "c2", "c3"), rows.map { it.chapterId })
        assertEquals(listOf("RSS", "Hacker News", "RSS"), rows.map { it.sourceLabel })
    }

    @Test
    fun `duplicate chapter ids are dropped`() {
        val rows = FeedLogic.rows(
            unread = listOf(unread("rss:a", "c1"), unread("rss:a", "c1")),
            sourceIdOf = sourceOf,
            labelOf = labels::get,
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun `unknown source falls back to id and blank title falls back to publisher`() {
        val rows = FeedLogic.rows(
            unread = listOf(unread("mystery:x", "c9", book = "Some Blog", chapter = "  ")),
            sourceIdOf = sourceOf,
            labelOf = labels::get,
        )
        assertEquals("mystery", rows.single().sourceLabel)
        assertEquals("Some Blog", rows.single().title)
    }

    @Test
    fun `queue carries every row in order`() {
        val rows = FeedLogic.rows(listOf(unread("rss:a", "c1"), unread("rss:b", "c2")), sourceOf, labels::get)
        val q = FeedLogic.queue(rows)
        assertEquals(listOf("rss:a" to "c1", "rss:b" to "c2"), q.map { it.fictionId to it.chapterId })
        assertEquals("rss", q.first().sourceId)
    }

    @Test
    fun `playing index only lights up for the feed's own queue`() {
        val rows = FeedLogic.rows(listOf(unread("rss:a", "c1"), unread("rss:b", "c2")), sourceOf, labels::get)
        assertEquals(1, FeedLogic.playingIndex(rows, listOf("c1", "c2"), "c2"))
        // A different queue (e.g. a Morning Briefing) that shares an item.
        assertEquals(-1, FeedLogic.playingIndex(rows, listOf("c2", "zz"), "c2"))
        assertEquals(-1, FeedLogic.playingIndex(rows, null, null))
    }

    @Test
    fun `preview collapses whitespace and cuts at a word boundary`() {
        val body = "The  council met\n\non Tuesday to discuss the new library hours, and " + "word ".repeat(60)
        val p = FeedLogic.preview(body, title = "Council news")!!
        assertEquals(true, p.startsWith("The council met on Tuesday"))
        assertEquals(true, p.length <= FeedLogic.PREVIEW_CHARS + 1)
        assertEquals(true, p.endsWith("…"))
        assertEquals(false, p.contains("  "))
    }

    @Test
    fun `preview is null when blank or just the title again`() {
        assertEquals(null, FeedLogic.preview(null, title = "T"))
        assertEquals(null, FeedLogic.preview("   \n ", title = "T"))
        assertEquals(null, FeedLogic.preview("Council news", title = "Council news"))
    }

    @Test
    fun `short preview is kept whole`() {
        assertEquals("A short post.", FeedLogic.preview("A short post.", title = "T"))
    }

    @Test
    fun `rows carry the preview`() {
        val u = unread("rss:a", "c1", chapter = "Headline").copy(bodyPreview = "Body text here.")
        val rows = FeedLogic.rows(listOf(u), sourceOf, labels::get)
        assertEquals("Body text here.", rows.single().preview)
    }
}
