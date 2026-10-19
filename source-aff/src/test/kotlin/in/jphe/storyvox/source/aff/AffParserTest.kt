package `in`.jphe.storyvox.source.aff

import `in`.jphe.storyvox.source.aff.parser.AffParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AffParserTest {
    private val base = "https://hp.adult-fanfiction.org"

    @Test fun `story-entry cards parse`() {
        val r = AffParser.parseList(AffFixtures.LIST, base)
        assertEquals(1, r.entries.size)
        val e = r.entries[0]
        assertEquals("600099899", e.storyNo)
        assertEquals("The Long Winter", e.title)
        assertEquals("greeting", e.author)
        assertEquals(6, e.chapters)
        assertEquals(listOf("Angst", "Drama"), e.tags)
        assertTrue(r.hasNext)
    }

    @Test fun `story page chapters strip the Chapter N prefix`() {
        val s = AffParser.parseStory(AffFixtures.STORY, base)!!
        assertEquals("The Long Winter", s.title)
        assertEquals("greeting", s.author)
        assertEquals(listOf(1 to "Being back", 2 to "The Road"), s.chapters)
    }

    @Test fun `br-formatted body keeps paragraph breaks`() {
        val c = AffParser.parseChapter(AffFixtures.STORY, base)!!
        assertEquals("First line of the story.\n\n\"Second line,\" she said.", c.plain)
    }
}
