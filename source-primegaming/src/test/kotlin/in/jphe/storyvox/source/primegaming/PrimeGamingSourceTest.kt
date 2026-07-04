package `in`.jphe.storyvox.source.primegaming

import dagger.Lazy
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.primegaming.config.PrimeGamingConfig
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingApi
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingFeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1494 — end-to-end happy-path wiring for [PrimeGamingSource], with the
 * feed served from a canned parse (no network) by overriding the `open`
 * [PrimeGamingApi.feed].
 *
 * This exists on purpose alongside the contract kit: the kit's happy-path check
 * only asserts that a request was *made* on an IO thread, not that the body was
 * served and mapped to `Success` (see reverie's dx #1523 — a wrong
 * `listPathFragment` 404s but still passes the IO-pin check). These tests assert
 * the body actually becomes a collection + chapters, so a routing/mapping
 * regression fails loudly instead of silently false-greening.
 */
class PrimeGamingSourceTest {

    private val atom = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en">
          <title>Free Amazon Prime Games (PC)</title>
          <updated>2026-07-02T18:00:48.767Z</updated>
          <entry>
            <id>https://feed.eikowagenknecht.com/lootscraper/10498</id>
            <title>Amazon Prime (Game) - A Rat's Quest: The Way Back Home</title>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><ul><li><b>Offer valid from:</b> 2026-04-09 18:00</li><li><b>Offer valid to:</b> 2026-07-08 00:00</li><ul><li><b>Description:</b> Follow Mat home.</li></ul></ul><p>Claim on <a href="https://luna.amazon.com/claims/a-rats-quest">Amazon Prime</a>.</p></div></content>
            <link href="https://luna.amazon.com/claims/a-rats-quest"/>
            <category term="Genre: Adventure" label="Adventure"/>
            <category term="Genre: Indie" label="Indie"/>
          </entry>
          <entry>
            <id>https://feed.eikowagenknecht.com/lootscraper/10512</id>
            <title>Amazon Prime (Game) - Mafia III: Definitive Edition</title>
            <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><ul><li><b>Offer valid to:</b> 2026-09-23 00:00</li><ul><li><b>Description:</b> New Bordeaux, 1968.</li></ul></ul><p>Claim on <a href="https://luna.amazon.com/claims/mafia-iii">Amazon Prime</a>.</p></div></content>
            <link href="https://luna.amazon.com/claims/mafia-iii"/>
            <category term="Genre: Action" label="Action"/>
          </entry>
        </feed>
    """.trimIndent()

    private fun sourceReturning(result: FictionResult<PrimeGamingApi.FeedFetch>): PrimeGamingSource {
        val cfg = object : PrimeGamingConfig {
            override val feedUrlFlow: Flow<String> = emptyFlow()
            override suspend fun feedUrl(): String = "https://example.test/feed.xml"
            override suspend fun setFeedUrl(url: String?) = Unit
        }
        val api = object : PrimeGamingApi(
            OkHttpClient(),
            object : Lazy<PrimeGamingConfig> { override fun get(): PrimeGamingConfig = cfg },
        ) {
            override suspend fun feed(forceRefresh: Boolean): FictionResult<FeedFetch> = result
        }
        return PrimeGamingSource(api)
    }

    private fun happySource(): PrimeGamingSource =
        sourceReturning(FictionResult.Success(PrimeGamingApi.FeedFetch(PrimeGamingFeed.parse(atom), "rev-1")))

    private fun <T> FictionResult<T>.success(): T {
        assertTrue("expected Success, got $this", this is FictionResult.Success)
        return (this as FictionResult.Success).value
    }

    @Test
    fun `popular surfaces one collection fiction with the honest Prime caveat`() {
        val page = runBlocking { happySource().popular(1) }.success()
        assertEquals(1, page.items.size)
        assertFalse(page.hasNext)
        val fiction = page.items.first()
        assertEquals("Prime Gaming Free Games", fiction.title)
        assertEquals(2, fiction.chapterCount)
        assertTrue(
            "collection description must state a Prime subscription is required",
            fiction.description!!.contains("Prime subscription", ignoreCase = true),
        )
    }

    @Test
    fun `popular page 2 is empty so pagination terminates`() {
        val page = runBlocking { happySource().popular(2) }.success()
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `fictionDetail lists each claim as a chapter`() {
        val src = happySource()
        val fid = runBlocking { src.popular(1) }.success().items.first().id
        val detail = runBlocking { src.fictionDetail(fid) }.success()
        assertEquals(2, detail.chapters.size)
        assertTrue(detail.chapters.map { it.title }.contains("Mafia III: Definitive Edition"))
        assertEquals(listOf("Action", "Adventure", "Indie"), detail.genres.sorted())
    }

    @Test
    fun `unknown fiction id is NotFound`() {
        assertTrue(runBlocking { happySource().fictionDetail("not-a-fiction") } is FictionResult.NotFound)
    }

    @Test
    fun `chapter returns narratable content carrying the Prime caveat and claim link`() {
        val src = happySource()
        val fid = runBlocking { src.popular(1) }.success().items.first().id
        val chId = runBlocking { src.fictionDetail(fid) }.success().chapters.first().id
        val content = runBlocking { src.chapter(fid, chId) }.success()
        assertTrue(content.plainBody.contains("Amazon Prime subscription"))
        assertTrue(content.plainBody.contains("luna.amazon.com"))
        assertTrue(content.htmlBody.isNotBlank())
    }

    @Test
    fun `missing chapter id is NotFound`() {
        val src = happySource()
        val fid = runBlocking { src.popular(1) }.success().items.first().id
        assertTrue(runBlocking { src.chapter(fid, "99999") } is FictionResult.NotFound)
    }

    @Test
    fun `search matches a game title, misses an unrelated term, and blank returns the collection`() {
        val src = happySource()
        assertEquals(1, runBlocking { src.search(SearchQuery(term = "mafia")) }.success().items.size)
        assertEquals(0, runBlocking { src.search(SearchQuery(term = "zzz-no-such-game")) }.success().items.size)
        assertEquals(1, runBlocking { src.search(SearchQuery(term = "")) }.success().items.size)
    }

    @Test
    fun `a feed failure propagates as a typed failure, and genres degrades to empty`() {
        val down = sourceReturning(FictionResult.NetworkError("feed down"))
        assertTrue(runBlocking { down.popular(1) } is FictionResult.NetworkError)
        // Genre picker must not error out on a feed hiccup.
        val genres = runBlocking { down.genres() }.success()
        assertTrue(genres.isEmpty())
    }
}
