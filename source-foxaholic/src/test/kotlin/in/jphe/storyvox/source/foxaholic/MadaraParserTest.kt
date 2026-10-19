package `in`.jphe.storyvox.source.foxaholic

import `in`.jphe.storyvox.source.foxaholic.parser.MadaraParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MadaraParserTest {
    private val base = "https://www.foxaholic.com"

    @Test fun `archive card with lazy-loaded cover`() {
        val r = MadaraParser.parseList(FoxaholicFixtures.LIST, base)
        val c = r.cards.single()
        assertEquals("moonlit-court", c.slug)
        assertEquals("Moonlit Court", c.title)
        assertEquals("https://www.foxaholic.com/wp-content/uploads/moon.jpg", c.cover)
        assertTrue(r.hasNext)
    }

    @Test fun `novel page strips badges and reads status`() {
        val n = MadaraParser.parseNovel(FoxaholicFixtures.NOVEL, base)!!
        assertEquals("Moonlit Court", n.title)
        assertEquals("Lan Yue", n.author)
        assertEquals(listOf("Romance", "Historical"), n.genres)
        assertTrue(n.completed)
    }

    @Test fun `chapter rows skip premium and come out oldest first`() {
        val rows = MadaraParser.parseChapterRows(FoxaholicFixtures.CHAPTERS, "moonlit-court")
        assertEquals(listOf("chapter-1", "vol-1/chapter-2", "chapter-3"), rows.map { it.path })
    }

    @Test fun `chapter text drops ad blocks`() {
        val c = MadaraParser.parseChapter(FoxaholicFixtures.CHAPTER, base)!!
        assertEquals("The gates opened at dawn.\n\nNo one bowed.", c.plain)
    }
}
