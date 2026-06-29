package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1238 — the section catalog + id/URL routing logic (pure, no
 * network) and the chapter-id scheme.
 */
class GoogleNewsCatalogTest {

    @Test
    fun `catalog is Top stories plus the 8 topic sections`() {
        val catalog = GoogleNewsSections.catalog()
        assertEquals(9, catalog.size)
        assertEquals(GoogleNewsSections.TOP_ID, catalog.first().fictionId)
        assertEquals(8, GoogleNewsSections.TOPICS.size)
    }

    @Test
    fun `every catalog feed url targets the news_google_com rss host`() {
        GoogleNewsSections.catalog().forEach {
            assertTrue(
                "unexpected feed url: ${it.feedUrl}",
                it.feedUrl.startsWith("https://news.google.com/rss"),
            )
            // round-trips: the id alone resolves back to a feed URL.
            assertNotNull(GoogleNewsSections.feedUrlFor(it.fictionId))
        }
    }

    @Test
    fun `feedUrlFor maps top, topic, and rejects unknown ids`() {
        assertEquals(
            "https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en",
            GoogleNewsSections.feedUrlFor(GoogleNewsSections.TOP_ID),
        )
        assertNotNull(GoogleNewsSections.feedUrlFor("${SourceIds.GOOGLE_NEWS}:topic:TECHNOLOGY"))
        assertNull("bogus topic must not resolve", GoogleNewsSections.feedUrlFor("${SourceIds.GOOGLE_NEWS}:topic:BOGUS"))
        assertNull("foreign id must not resolve", GoogleNewsSections.feedUrlFor("rss:whatever"))
    }

    @Test
    fun `search id round-trips through feed url and display title`() {
        val id = GoogleNewsSections.searchFictionId("climate tech")
        assertFalse("search id must not carry raw spaces", id.contains(" "))
        val url = GoogleNewsSections.feedUrlFor(id)
        assertNotNull(url)
        assertTrue(url!!.contains("/search?q="))
        assertTrue(url.contains("climate"))
        assertEquals("Search: climate tech", GoogleNewsSections.titleFor(id))
    }

    @Test
    fun `titleFor resolves topic and top ids`() {
        assertEquals("Top stories", GoogleNewsSections.titleFor(GoogleNewsSections.TOP_ID))
        assertEquals("Technology", GoogleNewsSections.titleFor("${SourceIds.GOOGLE_NEWS}:topic:TECHNOLOGY"))
    }

    @Test
    fun `isGoogleNewsId accepts own ids and rejects foreign ones`() {
        assertTrue(GoogleNewsSections.isGoogleNewsId(GoogleNewsSections.TOP_ID))
        assertTrue(GoogleNewsSections.isGoogleNewsId("${SourceIds.GOOGLE_NEWS}:topic:SCIENCE"))
        assertFalse(GoogleNewsSections.isGoogleNewsId("hackernews:42"))
    }

    @Test
    fun `chapter id is stable per guid and scoped to its fiction`() {
        val item = GoogleNewsItem("Headline", "Publisher", "link", "guid-123", null, emptyList())
        val fid = GoogleNewsSections.TOP_ID
        assertEquals("stable across calls", item.toChapterId(fid), item.toChapterId(fid))
        assertTrue(item.toChapterId(fid).startsWith("$fid::"))
        assertNotEquals(
            "different guid must yield a different chapter id",
            item.toChapterId(fid),
            item.copy(guid = "guid-999").toChapterId(fid),
        )
    }

    @Test
    fun `google news rebuilds from its id alone`() {
        // Section/search ids are self-describing, so the source must NOT
        // be in the "needs the source URL to rebuild" set (#989).
        assertFalse(SourceIds.idNeedsSourceUrlToRebuild(SourceIds.GOOGLE_NEWS))
    }
}
