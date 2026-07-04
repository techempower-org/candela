package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.googlenews.article.ArticleResolver
import `in`.jphe.storyvox.source.googlenews.net.GoogleNewsApi
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1491 — direct MockWebServer coverage of the new [GoogleNewsApi] base-url seam
 * and status mapping over the real network path (`fictionDetail`). The contract
 * kit ([GoogleNewsContractTest]) covers 401 / 403-CF / 429 / IO-pin; this pins
 * the branch the kit doesn't exercise — a plain (non-Cloudflare) 403 -> AuthRequired
 * — plus the happy parse and that the seam preserves the `/rss` feed path.
 */
class GoogleNewsApiMappingTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun source(): GoogleNewsSource {
        val host = server.url("/").toString().trimEnd('/')
        val api = object : GoogleNewsApi(OkHttpClient()) {
            override val baseUrl: String get() = host
        }
        return GoogleNewsSource(api, NoOpResolver)
    }

    @Test
    fun `plain 403 without a cloudflare body maps to AuthRequired`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("<html><body>Forbidden</body></html>"))
        val r = runBlocking { source().fictionDetail(GoogleNewsSections.TOP_ID) }
        assertTrue("expected AuthRequired, got $r", r is FictionResult.AuthRequired)
    }

    @Test
    fun `429 maps to RateLimited`() {
        server.enqueue(MockResponse().setResponseCode(429))
        val r = runBlocking { source().fictionDetail(GoogleNewsSections.TOP_ID) }
        assertTrue("expected RateLimited, got $r", r is FictionResult.RateLimited)
    }

    @Test
    fun `happy feed parses over the base-url seam and keeps the rss path`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(FEED_BODY))
        val r = runBlocking { source().fictionDetail(GoogleNewsSections.TOP_ID) }
        assertTrue("expected Success, got $r", r is FictionResult.Success)
        assertEquals(1, (r as FictionResult.Success).value.chapters.size)
        // The seam swapped only the origin — the /rss feed path must survive so
        // section/topic/search routing is unchanged in production.
        assertTrue(server.takeRequest().path!!.startsWith("/rss"))
    }

    private object NoOpResolver : ArticleResolver {
        override suspend fun resolve(item: GoogleNewsItem): String? = null
    }

    companion object {
        const val FEED_BODY: String =
            """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel>""" +
                """<title>Top stories</title><item><title>Headline</title>""" +
                """<link>https://news.google.com/articles/CBMiX</link><guid>g1</guid></item>""" +
                """</channel></rss>"""
    }
}
