package `in`.jphe.storyvox.source.tapas

import `in`.jphe.storyvox.source.tapas.net.TapasApi
import `in`.jphe.storyvox.source.tapas.parser.TapasParser
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TapasParserTest {
    private val base = "https://tapas.io"

    @Test fun `search merges thumb and title anchors into one card per series`() {
        val r = TapasParser.parseSearch(TapasFixtures.SEARCH, base, page = 1)
        assertEquals(listOf("151010", "99001"), r.cards.map { it.seriesId })
        val c = r.cards.first()
        assertEquals("Dragontail1", c.slug)
        assertEquals("Dragontail:The firs wolfkin dragon", c.title)
        assertEquals("https://us-a.tapas.io/sa/41/cover_m.jpg", c.cover)
        assertTrue(r.hasNext)
    }

    @Test fun `series info parses title from the page title, plus creator and genres`() {
        val i = TapasParser.parseInfo(TapasFixtures.INFO, base)
        assertEquals("151010", i.seriesId)
        assertEquals("Dragontail:The firs wolfkin dragon", i.title)
        assertEquals("lezdeath50", i.author)
        assertEquals("I am Red Drago and I am 10 and I am dragon wolfkin", i.description)
        assertEquals(listOf("Fantasy"), i.genres)
    }

    @Test fun `episodes JSON keeps free episodes readable and flags the locked one`() {
        val p = TapasParser.parseEpisodes(TapasFixtures.EPISODES)!!
        assertEquals(3, p.episodes.size)
        assertEquals(listOf(true, true, false), p.episodes.map { it.readable })
        assertEquals("1516649", p.episodes[0].id)
        assertEquals(1_568_345_467_000L, p.episodes[0].publishedAt)
        assertFalse(p.hasNext)
    }

    @Test fun `episodes parse survives a non-JSON body`() {
        assertNull(TapasParser.parseEpisodes("<html>oops</html>"))
    }

    @Test fun `episode page strips scripts and the ad section`() {
        val c = TapasParser.parseEpisodePage(TapasFixtures.EPISODE, base)!!
        assertEquals("1", c.title)
        assertEquals("This is my story my name is Red Drago.\n\nMy father is a dragon.", c.plain)
        assertFalse(c.html.contains("ads()"))
    }

    @Test fun `pasted series URLs resolve to slugs, other hosts don't`() {
        val source = TapasSource(TapasApi(OkHttpClient()))
        assertEquals("Dragontail1", source.seriesSlug("https://tapas.io/series/Dragontail1"))
        assertEquals("Dragontail1", source.seriesSlug("/series/Dragontail1/info"))
        assertNull(source.seriesSlug("https://example.com/series/Dragontail1"))
        assertNull(source.seriesSlug("dragons"))
    }
}
