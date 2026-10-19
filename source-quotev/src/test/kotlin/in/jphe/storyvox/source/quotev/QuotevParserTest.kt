package `in`.jphe.storyvox.source.quotev

import `in`.jphe.storyvox.source.quotev.net.QuotevApi
import `in`.jphe.storyvox.source.quotev.parser.QuotevParser
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QuotevParserTest {
    private val base = "https://www.quotev.com"

    @Test fun `listing dedupes story anchors and ignores non-story links`() {
        val cards = QuotevParser.parseList(QuotevFixtures.LIST, base)
        assertEquals(listOf("14503869", "222"), cards.map { it.ref.storyId })
        assertEquals("The Last Door", cards[0].title)
        assertEquals("The-Last-Door", cards[0].ref.slug)
    }

    @Test fun `story page parses title, author, description and chapter numbers`() {
        val s = QuotevParser.parseStory(QuotevFixtures.STORY, base)!!
        assertEquals("The Last Door", s.title)
        assertEquals("Doorwright", s.author)
        assertEquals("A house with one door too many.", s.description)
        assertEquals(listOf(1, 2), s.chapterNumbers)
    }

    @Test fun `chapter content comes from rescontent with scripts dropped`() {
        val c = QuotevParser.parseChapter(QuotevFixtures.CHAPTER, base)!!
        assertEquals("The house had eleven doors.\n\nWe only built ten.", c.plain)
        assertFalse(c.html.contains("x()"))
    }

    @Test fun `input accepts ids and own-host URLs only`() {
        val source = QuotevSource(QuotevApi(OkHttpClient()))
        assertEquals("14503869", source.parseInput("14503869")?.storyId)
        assertEquals("14503869", source.parseInput("https://www.quotev.com/story/14503869/The-Last-Door/2")?.storyId)
        assertNull(source.parseInput("https://example.com/story/14503869/x"))
        assertNull(source.parseInput("spooky houses"))
    }
}
