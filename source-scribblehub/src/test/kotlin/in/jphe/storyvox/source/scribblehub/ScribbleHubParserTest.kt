package `in`.jphe.storyvox.source.scribblehub

import `in`.jphe.storyvox.source.scribblehub.parser.ScribbleHubParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScribbleHubParserTest {
    private val base = "https://www.scribblehub.com"

    @Test fun `ranking cards parse id, slug, genres and chapter count`() {
        val r = ScribbleHubParser.parseList(ScribbleHubFixtures.LIST, base)
        val c = r.cards.single()
        assertEquals("123456", c.ref.seriesId)
        assertEquals("the-last-alchemist", c.ref.slug)
        assertEquals("The Last Alchemist", c.title)
        assertEquals(87, c.chapters)
        assertEquals(listOf("Fantasy", "Adventure"), c.genres)
        assertEquals("A disgraced alchemist rebuilds her craft.", c.description)
        assertTrue(r.hasNext)
    }

    @Test fun `series page`() {
        val s = ScribbleHubParser.parseSeries(ScribbleHubFixtures.SERIES, base)!!
        assertEquals("Quill", s.author)
        assertEquals(listOf("Alchemy", "Female Protagonist"), s.tags)
        assertFalse(s.completed)
    }

    @Test fun `toc is put in reading order`() {
        val toc = ScribbleHubParser.parseToc(ScribbleHubFixtures.TOC, base)
        assertEquals(listOf("111", "222"), toc.map { it.chapterId })
    }

    @Test fun `chapter drops author notes from narration`() {
        val c = ScribbleHubParser.parseChapter(ScribbleHubFixtures.CHAPTER, base)!!
        assertEquals("The furnace was cold.\n\nShe lit it anyway.", c.plain)
        assertEquals("Chapter 1: Smoke", c.title)
    }
}
