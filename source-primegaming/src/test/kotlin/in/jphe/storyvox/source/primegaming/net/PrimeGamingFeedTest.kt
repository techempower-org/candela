package `in`.jphe.storyvox.source.primegaming.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1494 — pins the LootScraper Atom layout the regex parser keys off.
 *
 * The load-bearing assertion is [content extracted from xhtml, not empty]: the
 * narratable payload lives in `<content type="xhtml">` as a nested `<div>`, so a
 * naive `.text`/`nextText()` read returns empty. If a future refactor swaps the
 * regex parse for an XmlPullParser and reintroduces that bug, this test fails
 * instead of shipping empty chapters.
 */
class PrimeGamingFeedTest {

    private val feedXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en">
          <id>https://feed.eikowagenknecht.com/lootscraper/amazon</id>
          <title>Free Amazon Prime Games (PC)</title>
          <updated>2026-07-02T18:00:48.767Z</updated>
          <entry>
            <id>https://feed.eikowagenknecht.com/lootscraper/10498</id>
            <title>Amazon Prime (Game) - A Rat&#039;s Quest: The Way Back Home</title>
            <updated>2026-04-09T18:00:52.977Z</updated>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><img src="x.jpg" /><ul><li><b>Offer valid from:</b> 2026-04-09 18:00</li><li><b>Offer valid to:</b> 2026-07-08 00:00</li><ul><li><b>Release date:</b> 2026-04-03</li><li><b>Description:</b> Follow Mat &amp; friends on a rodent&#039;s adventure.</li><li><b>Genres:</b> Action, Adventure, Indie</li></ul></ul><p>Claim it now for free on <a href="https://luna.amazon.com/claims/a-rats-quest">Amazon Prime</a>.</p></div></content>
            <link href="https://luna.amazon.com/claims/a-rats-quest"/>
            <category term="Genre: Action" label="Action"/>
            <category term="Genre: Adventure" label="Adventure"/>
            <category term="Genre: Indie" label="Indie"/>
          </entry>
          <entry>
            <id>https://feed.eikowagenknecht.com/lootscraper/10512</id>
            <title>Amazon Prime (Game) - Space Grunts</title>
            <updated>2026-06-25T17:00:49.584Z</updated>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><ul><li><b>Offer valid from:</b> 2026-06-25 17:00</li><li><b>Offer valid to:</b> 2026-09-23 00:00</li><ul><li><b>Description:</b> Fight your way through a moon base.</li></ul></ul><p>Claim it on <a href="https://luna.amazon.com/claims/space-grunts">Amazon Prime</a>.</p></div></content>
            <link href="https://luna.amazon.com/claims/space-grunts"/>
          </entry>
        </feed>
    """.trimIndent()

    @Test
    fun `parses feed-level title and both entries`() {
        val feed = PrimeGamingFeed.parse(feedXml)
        assertEquals("Free Amazon Prime Games (PC)", feed.title)
        assertEquals(2, feed.entries.size)
    }

    @Test
    fun `strips the Amazon Prime prefix and decodes entities in the game title`() {
        val first = PrimeGamingFeed.parse(feedXml).entries[0]
        assertEquals("A Rat's Quest: The Way Back Home", first.game)
    }

    @Test
    fun `uses the trailing lootscraper number as a stable id`() {
        val feed = PrimeGamingFeed.parse(feedXml)
        assertEquals("10498", feed.entries[0].id)
        assertEquals("10512", feed.entries[1].id)
    }

    @Test
    fun `content extracted from xhtml, not empty (the trap regression test)`() {
        val first = PrimeGamingFeed.parse(feedXml).entries[0]
        // Description lives inside the xhtml content div — must be non-empty.
        assertNotNull(first.description)
        assertTrue(first.description!!.isNotBlank())
        // Entity decoding: &amp; -> &, &#039; -> '
        assertEquals("Follow Mat & friends on a rodent's adventure.", first.description)
    }

    @Test
    fun `extracts the claim window and release date`() {
        val first = PrimeGamingFeed.parse(feedXml).entries[0]
        assertEquals("2026-04-09 18:00", first.validFrom)
        assertEquals("2026-07-08 00:00", first.validTo)
        assertEquals("2026-04-03", first.releaseDate)
    }

    @Test
    fun `reads genres from category labels`() {
        val first = PrimeGamingFeed.parse(feedXml).entries[0]
        assertEquals(listOf("Action", "Adventure", "Indie"), first.genres)
    }

    @Test
    fun `falls back to inline genres when no category tags`() {
        // Second entry has no <category> tags; there is no inline Genres line
        // either, so genres is empty (not a crash).
        val second = PrimeGamingFeed.parse(feedXml).entries[1]
        assertTrue(second.genres.isEmpty())
    }

    @Test
    fun `extracts the Amazon claim url`() {
        val first = PrimeGamingFeed.parse(feedXml).entries[0]
        assertEquals("https://luna.amazon.com/claims/a-rats-quest", first.claimUrl)
    }

    @Test
    fun `empty or junk input yields an empty feed, not a crash`() {
        assertTrue(PrimeGamingFeed.parse("").entries.isEmpty())
        assertTrue(PrimeGamingFeed.parse("<html>not a feed</html>").entries.isEmpty())
    }
}
