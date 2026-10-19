package `in`.jphe.storyvox.source.inkitt

import `in`.jphe.storyvox.source.inkitt.net.InkittApi
import `in`.jphe.storyvox.source.inkitt.parser.InkittParser
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InkittParserTest {
    private val base = "https://www.inkitt.com"

    @Test fun `story page parses the Book block and the chapters component`() {
        val s = InkittParser.parseStoryPage(InkittFixtures.STORY, base)!!
        assertEquals("Watching eachother souls", s.title)
        assertEquals("Nandani Agarwal", s.author)
        assertEquals(listOf("romance", "mystery"), s.genres)
        assertEquals("16+", s.contentRating)
        assertTrue(s.ongoing)
        assertEquals("https://cdn-gcs.inkitt.com/cover.jpg", s.cover)
        assertEquals(listOf(1, 2), s.chapters.map { it.number })
        assertEquals("Meeting My Sister's Boyfriend", s.chapters[0].name)
        assertEquals(1_721_860_429_000L, s.updatedAt)
    }

    @Test fun `chapter page lifts the heading and keeps paragraphs`() {
        val c = InkittParser.parseChapterPage(InkittFixtures.CHAPTER, base)!!
        assertEquals("Meeting My Sister's Boyfriend", c.title)
        assertEquals("The doorbell rang twice.\n\nI knew before I opened it.", c.plain)
    }

    @Test fun `search reader survives the SPA shell and reads real JSON if it appears`() {
        assertEquals(0, InkittParser.parseSearchStories(InkittFixtures.SEARCH_HTML_SHELL).size)
        val hits = InkittParser.parseSearchStories(InkittFixtures.SEARCH_JSON)
        assertEquals(listOf("7" to "Watching eachother souls", "99" to "Other"), hits)
    }

    @Test fun `story ids parse from bare ids and URLs, not from other hosts`() {
        val source = InkittSource(InkittApi(OkHttpClient()))
        assertEquals("7", source.storyId("7"))
        assertEquals("7", source.storyId("https://www.inkitt.com/stories/7"))
        assertEquals("1448457", source.storyId("https://www.inkitt.com/stories/fantasy/1448457"))
        assertEquals("7", source.storyId("/stories/7/chapters/1"))
        assertNull(source.storyId("https://example.com/stories/7"))
        assertNull(source.storyId("vampire romance"))
    }
}
