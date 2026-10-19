package `in`.jphe.storyvox.source.literotica

import `in`.jphe.storyvox.source.literotica.net.LiteroticaApi
import `in`.jphe.storyvox.source.literotica.parser.LitJson
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LitJsonTest {

    @Test fun `list parses slugs from URLs and bare slugs alike, plus paging meta`() {
        val r = LitJson.parseList(LitFixtures.LIST)
        assertEquals(listOf("the-lighthouse-keeper", "second-watch"), r.stories.map { it.slug })
        val s = r.stories.first()
        assertEquals("The Lighthouse Keeper", s.title)
        assertEquals("SeaGlass", s.author)
        assertEquals("Romance", s.category)
        assertEquals(4.62, s.rating!!, 1e-6)
        assertEquals(3, s.pagesCount)
        // String-typed rating still parses on the second row.
        assertEquals(4.1, r.stories[1].rating!!, 1e-6)
        assertTrue(r.hasNext)
    }

    @Test fun `non-JSON and empty bodies degrade to an empty list`() {
        assertEquals(0, LitJson.parseList("<html>challenge</html>").stories.size)
        assertFalse(LitJson.parseList("{}").hasNext)
    }

    @Test fun `story page carries meta and converts plain text to paragraphs`() {
        val p = LitJson.parseStory(LitFixtures.STORY)!!
        assertEquals("the-lighthouse-keeper", p.story!!.slug)
        assertEquals(3, p.story!!.pagesCount)
        assertEquals(
            "The storm came in from the west.\n\nShe climbed the stairs with the oil can.",
            p.plain,
        )
        assertTrue(p.html.startsWith("<p>The storm came in from the west.</p>"))
    }

    @Test fun `story page passes HTML text through with a plain rendering`() {
        val p = LitJson.parseStory(LitFixtures.STORY_PAGE_2)!!
        assertEquals("Morning found the lamp still burning.\n\nHe was gone.", p.plain)
    }

    @Test fun `pasted story URLs resolve to slugs, other hosts don't`() {
        val source = LiteroticaSource(LiteroticaApi(OkHttpClient()))
        assertEquals("the-lighthouse-keeper", source.urlSlug("https://www.literotica.com/s/the-lighthouse-keeper"))
        assertEquals("the-lighthouse-keeper", source.urlSlug("/s/the-lighthouse-keeper"))
        assertNull(source.urlSlug("https://example.com/s/the-lighthouse-keeper"))
        assertNull(source.urlSlug("lighthouse"))
    }
}
