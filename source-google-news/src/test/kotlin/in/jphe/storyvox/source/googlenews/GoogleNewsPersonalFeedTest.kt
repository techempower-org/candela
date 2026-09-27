package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.repository.GoogleNewsEdition
import `in`.jphe.storyvox.data.repository.GoogleNewsPersonalFeed
import `in`.jphe.storyvox.data.repository.GoogleNewsTopic
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1678 — the personalized-feed model and its section routing (pure,
 * no network).
 */
class GoogleNewsPersonalFeedTest {

    private val personal = GoogleNewsPersonalFeed(
        topics = listOf(GoogleNewsTopic.TECHNOLOGY),
        locations = listOf("Oakland, California"),
        searches = listOf("climate tech"),
    )

    // ─── landing ─────────────────────────────────────────────────────────

    @Test
    fun `unpersonalized landing is exactly the classic catalog`() {
        val landing = GoogleNewsSections.landing(GoogleNewsPersonalFeed())
        assertEquals(GoogleNewsSections.catalog(), landing)
        assertFalse(landing.any { it.fictionId == GoogleNewsSections.FOR_YOU_ID })
    }

    @Test
    fun `personalized landing puts For you, Top, then followed sections first`() {
        val ids = GoogleNewsSections.landing(personal).map { it.fictionId }
        assertEquals(GoogleNewsSections.FOR_YOU_ID, ids[0])
        assertEquals(GoogleNewsSections.TOP_ID, ids[1])
        assertEquals(GoogleNewsSections.topicFictionId(GoogleNewsTopic.TECHNOLOGY), ids[2])
        assertEquals(GoogleNewsSections.geoFictionId("Oakland, California"), ids[3])
        assertEquals(GoogleNewsSections.searchFictionId("climate tech"), ids[4])
        // The 7 unfollowed topics follow; the followed one is not repeated.
        assertEquals(2 + 3 + 7, ids.size)
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `personal sections all resolve to rss feed urls`() {
        GoogleNewsSections.personalSections(personal).forEach {
            assertTrue(it.feedUrl, it.feedUrl.startsWith("https://news.google.com/rss"))
            assertEquals(it.feedUrl, GoogleNewsSections.feedUrlFor(it.fictionId))
        }
    }

    // ─── edition & locations ─────────────────────────────────────────────

    @Test
    fun `edition is applied to every feed url`() {
        val es = GoogleNewsEdition.ES_ES
        assertEquals(
            "https://news.google.com/rss?hl=es&gl=ES&ceid=ES:es",
            GoogleNewsSections.feedUrlFor(GoogleNewsSections.TOP_ID, es),
        )
        val topic = GoogleNewsSections.feedUrlFor(
            GoogleNewsSections.topicFictionId(GoogleNewsTopic.SPORTS),
            GoogleNewsEdition.MX_ES,
        )!!
        assertTrue(topic, topic.endsWith("/topic/SPORTS?hl=es-419&gl=MX&ceid=MX:es-419"))
    }

    @Test
    fun `default edition keeps the pre-personalization urls`() {
        assertEquals("hl=en-US&gl=US&ceid=US:en", GoogleNewsEdition.DEFAULT.queryString)
    }

    @Test
    fun `location feed url path-encodes spaces as percent-20`() {
        val url = GoogleNewsSections.feedUrlFor(GoogleNewsSections.geoFictionId("Oakland, California"))!!
        assertTrue(url, url.contains("/headlines/section/geo/Oakland%2C%20California?"))
        assertFalse(url, url.contains("+"))
    }

    @Test
    fun `location title is the place name`() {
        assertEquals("Oakland, California", GoogleNewsSections.titleFor(GoogleNewsSections.geoFictionId("Oakland, California")))
    }

    @Test
    fun `section titles follow the edition language`() {
        val es = GoogleNewsEdition.US_ES
        assertEquals("Noticias destacadas", GoogleNewsSections.titleFor(GoogleNewsSections.TOP_ID, es))
        assertEquals("Para ti", GoogleNewsSections.titleFor(GoogleNewsSections.FOR_YOU_ID, es))
        assertEquals("Tecnología", GoogleNewsSections.titleFor(GoogleNewsSections.topicFictionId(GoogleNewsTopic.TECHNOLOGY), es))
        assertEquals("Búsqueda: clima", GoogleNewsSections.titleFor(GoogleNewsSections.searchFictionId("clima"), es))
        assertEquals("EE. UU.", GoogleNewsSections.topicTitle(GoogleNewsTopic.NATION, es))
        assertEquals("Nacional", GoogleNewsSections.topicTitle(GoogleNewsTopic.NATION, GoogleNewsEdition.MX_ES))
        assertEquals("National", GoogleNewsSections.topicTitle(GoogleNewsTopic.NATION, GoogleNewsEdition.GB_EN))
    }

    @Test
    fun `For you is owned by the source but has no single feed url`() {
        assertTrue(GoogleNewsSections.isGoogleNewsId(GoogleNewsSections.FOR_YOU_ID))
        assertNull(GoogleNewsSections.feedUrlFor(GoogleNewsSections.FOR_YOU_ID))
        assertTrue(GoogleNewsSections.isGoogleNewsId(GoogleNewsSections.geoFictionId("Madrid")))
    }

    // ─── model ───────────────────────────────────────────────────────────

    @Test
    fun `terms are normalized, de-duplicated case-insensitively, and blanks ignored`() {
        val f = GoogleNewsPersonalFeed()
            .addSearch("  climate \n  tech ")
            .addSearch("CLIMATE TECH")
            .addSearch("   ")
        assertEquals(listOf("climate tech"), f.searches)
        assertEquals(emptyList<String>(), f.removeSearch("Climate Tech").searches)
    }

    @Test
    fun `lists are capped at MAX_ENTRIES`() {
        var f = GoogleNewsPersonalFeed()
        repeat(GoogleNewsPersonalFeed.MAX_ENTRIES + 5) { f = f.addLocation("Place $it") }
        assertEquals(GoogleNewsPersonalFeed.MAX_ENTRIES, f.locations.size)
    }

    @Test
    fun `withTopic follows and unfollows without duplicates`() {
        val f = GoogleNewsPersonalFeed()
            .withTopic(GoogleNewsTopic.SCIENCE, true)
            .withTopic(GoogleNewsTopic.SCIENCE, true)
        assertEquals(listOf(GoogleNewsTopic.SCIENCE), f.topics)
        assertTrue(f.isPersonalized)
        assertFalse(f.withTopic(GoogleNewsTopic.SCIENCE, false).isPersonalized)
    }

    @Test
    fun `unknown edition names fall back to the default`() {
        assertEquals(GoogleNewsEdition.DEFAULT, GoogleNewsEdition.fromName("XX_YY"))
        assertEquals(GoogleNewsEdition.DEFAULT, GoogleNewsEdition.fromName(null))
        assertEquals(GoogleNewsEdition.ES_ES, GoogleNewsEdition.fromName("ES_ES"))
    }

    // ─── interleave ──────────────────────────────────────────────────────

    private fun item(guid: String, title: String = guid) =
        GoogleNewsItem(title, "P", "link", guid, null, emptyList())

    @Test
    fun `interleave round-robins across feeds`() {
        val merged = interleave(
            listOf(listOf(item("a1"), item("a2"), item("a3")), listOf(item("b1"))),
            limit = 10,
        )
        assertEquals(listOf("a1", "b1", "a2", "a3"), merged.map { it.guid })
    }

    @Test
    fun `interleave drops duplicate guids and duplicate headlines`() {
        val merged = interleave(
            listOf(
                listOf(item("x", "Same story"), item("y")),
                listOf(item("x", "Same story"), item("z", "same STORY")),
            ),
            limit = 10,
        )
        assertEquals(listOf("x", "y"), merged.map { it.guid })
    }

    @Test
    fun `interleave honours the limit`() {
        val feed = (1..50).map { item("g$it") }
        assertEquals(7, interleave(listOf(feed, feed.map { item("h" + it.guid) }), limit = 7).size)
    }
}
