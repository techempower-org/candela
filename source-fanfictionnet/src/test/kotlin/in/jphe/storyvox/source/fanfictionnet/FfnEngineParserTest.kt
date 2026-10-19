package `in`.jphe.storyvox.source.fanfictionnet

import `in`.jphe.storyvox.source.fanfictionnet.engine.FfnEngineParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FfnEngineParserTest {
    private val base = "https://www.fanfiction.net"

    @Test fun `list cards parse id, title, author, summary and meta`() {
        val r = FfnEngineParser.parseList(FfnFixtures.LIST, base)
        assertEquals(2, r.items.size)
        val first = r.items[0]
        assertEquals("2636963", first.storyId)
        assertEquals("Harry Potter and the Methods of Rationality", first.title)
        assertEquals("Less Wrong", first.author)
        assertEquals("Petunia married a biochemist, and Harry grew up reading science.", first.summary)
        assertEquals(122, first.chapters)
        assertTrue(first.complete)
        assertEquals("https://www.fanfiction.net/image/180/75/", first.coverUrl)
        assertFalse(r.items[1].complete)
        assertTrue(r.hasNext)
    }

    @Test fun `story header and de-duplicated chapter list`() {
        val h = FfnEngineParser.parseStory(FfnFixtures.STORY, base, "2636963")!!
        assertEquals("Harry Potter and the Methods of Rationality", h.title)
        assertEquals("Less Wrong", h.author)
        assertEquals("1004015", h.authorId)
        assertTrue(h.complete)
        assertEquals(listOf("Drama", "Humor"), h.genres)
        assertEquals(661_619L, h.words)
        assertEquals(1426316400_000L, h.updatedAt)
        assertEquals(3, h.chapterTitles.size)
        assertEquals(2 to "Everything I Believe Is False", h.chapterTitles[1])
        assertEquals(listOf("Books", "Harry Potter"), h.categories)
    }

    @Test fun `chapter body drops ads and keeps paragraphs`() {
        val c = FfnEngineParser.parseChapter(FfnFixtures.STORY, base, 1)!!
        assertEquals("A Day of Very Low Probability", c.title)
        assertEquals(
            "Beneath the moonlight glints a tiny fragment of silver.\n\nEvery inch of wall space is covered by a bookcase.",
            c.plain,
        )
    }

    @Test fun `not-found page yields null`() {
        assertNull(FfnEngineParser.parseStory(FfnFixtures.NOT_FOUND, base, "1"))
    }
}
