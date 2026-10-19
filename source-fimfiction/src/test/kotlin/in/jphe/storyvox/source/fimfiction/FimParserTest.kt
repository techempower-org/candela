package `in`.jphe.storyvox.source.fimfiction

import `in`.jphe.storyvox.source.fimfiction.parser.FimHtmlParser
import `in`.jphe.storyvox.source.fimfiction.parser.FimJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FimParserTest {
    private val base = "https://www.fimfiction.net"

    @Test fun `story JSON parses meta, author, categories and chapters`() {
        val s = FimJson.parseStory(FimFixtures.STORY_JSON)!!.story!!
        assertEquals(141549L, s.id)
        assertEquals("The Celestia Code", s.title)
        assertEquals("iisaw", s.author?.name)
        assertEquals("Complete", s.status)
        assertEquals("Teen", s.contentRatingText)
        assertEquals(68566L, s.words)
        assertEquals(listOf("Adventure", "Mystery"), s.categories!!.filterValues { it }.keys.toList())
        assertEquals(listOf(428863L, 428864L), s.chapters.map { it.id })
        assertEquals(2329, s.chapters[0].words)
    }

    @Test fun `API error shape maps to a null story`() {
        val e = FimJson.parseStory(FimFixtures.STORY_ERROR_JSON)!!
        assertNull(e.story)
        assertEquals("Invalid story id", e.error)
    }

    @Test fun `HTML error page is not mistaken for API JSON`() {
        assertNull(FimJson.parseStory("<html><body>Gateway timeout</body></html>"))
    }

    @Test fun `listing cards parse id, author, tags and paging`() {
        val r = FimHtmlParser.parseList(FimFixtures.LIST, base)
        assertEquals(listOf("141549", "9999"), r.cards.map { it.storyId })
        val c = r.cards.first()
        assertEquals("The Celestia Code", c.title)
        assertEquals("iisaw", c.author)
        assertEquals("https://cdn-img.fimfiction.net/story/pzq1-141549-medium", c.cover)
        assertEquals(listOf("Adventure", "Mystery"), c.tags)
        assertTrue(r.hasNext)
    }

    @Test fun `a listing class rename degrades to sparse cards, not an empty page`() {
        val r = FimHtmlParser.parseList(FimFixtures.LIST_RENAMED, base)
        assertEquals(listOf("141549", "9999"), r.cards.map { it.storyId })
    }

    @Test fun `chapter export keeps paragraphs and lifts the heading out of the body`() {
        val c = FimHtmlParser.parseChapterDownload(FimFixtures.CHAPTER, base)!!
        assertEquals("1 The Treasure Room", c.title)
        assertEquals(
            "The Royal Archives smelled of dust and old parchment.\n\nTwilight lit her horn.",
            c.plain,
        )
    }
}
