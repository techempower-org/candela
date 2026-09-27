package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.repository.GoogleNewsEdition
import `in`.jphe.storyvox.data.repository.GoogleNewsPersonalFeed
import `in`.jphe.storyvox.data.repository.GoogleNewsTopic
import `in`.jphe.storyvox.data.repository.InMemoryGoogleNewsFeedStore
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.googlenews.article.ArticleResolver
import `in`.jphe.storyvox.source.googlenews.net.GoogleNewsApi
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Issue #1678 — the personalized feed over the network path: the merged
 * "For you" section, the edition reaching the wire, and the Browse landing.
 */
class GoogleNewsForYouTest {

    private lateinit var server: MockWebServer
    private val requested = Collections.synchronizedList(mutableListOf<String>())

    /** Path fragment → response. Unmatched paths 404. */
    private var routes: Map<String, MockResponse> = emptyMap()

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                requested += path
                return routes.entries.firstOrNull { path.contains(it.key) }?.value
                    ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun source(feed: GoogleNewsPersonalFeed): GoogleNewsSource {
        val host = server.url("/").toString().trimEnd('/')
        val api = object : GoogleNewsApi(OkHttpClient()) {
            override val baseUrl: String get() = host
        }
        return GoogleNewsSource(api, NoOpResolver, InMemoryGoogleNewsFeedStore(feed))
    }

    private val feed = GoogleNewsPersonalFeed(
        edition = GoogleNewsEdition.US_ES,
        topics = listOf(GoogleNewsTopic.SCIENCE),
        searches = listOf("clima"),
    )

    @Test
    fun `For you merges every followed section round-robin`() {
        routes = mapOf(
            "/topic/SCIENCE" to ok(rss("s1", "s2")),
            "/search?q=clima" to ok(rss("c1", "c2")),
        )
        val r = runBlocking { source(feed).fictionDetail(GoogleNewsSections.FOR_YOU_ID) }
        assertTrue("expected Success, got $r", r is FictionResult.Success)
        val detail = (r as FictionResult.Success).value
        assertEquals(listOf("s1", "c1", "s2", "c2"), detail.chapters.map { it.title })
        assertEquals("Para ti", detail.summary.title)
        assertTrue(detail.chapters.all { it.id.startsWith("${GoogleNewsSections.FOR_YOU_ID}::") })
    }

    @Test
    fun `For you survives one failing section`() {
        routes = mapOf(
            "/topic/SCIENCE" to MockResponse().setResponseCode(500),
            "/search?q=clima" to ok(rss("c1")),
        )
        val r = runBlocking { source(feed).fictionDetail(GoogleNewsSections.FOR_YOU_ID) }
        assertTrue("expected Success, got $r", r is FictionResult.Success)
        assertEquals(listOf("c1"), (r as FictionResult.Success).value.chapters.map { it.title })
    }

    @Test
    fun `For you surfaces the failure when every section fails`() {
        routes = mapOf("/rss" to MockResponse().setResponseCode(429))
        val r = runBlocking { source(feed).fictionDetail(GoogleNewsSections.FOR_YOU_ID) }
        assertTrue("expected RateLimited, got $r", r is FictionResult.RateLimited)
    }

    @Test
    fun `For you with nothing followed is NotFound, not a crash`() {
        val r = runBlocking { source(GoogleNewsPersonalFeed()).fictionDetail(GoogleNewsSections.FOR_YOU_ID) }
        assertTrue("expected NotFound, got $r", r is FictionResult.NotFound)
    }

    @Test
    fun `a merged chapter resolves back to its story`() {
        routes = mapOf(
            "/topic/SCIENCE" to ok(rss("s1")),
            "/search?q=clima" to ok(rss("c1")),
        )
        val src = source(feed)
        val detail = (runBlocking { src.fictionDetail(GoogleNewsSections.FOR_YOU_ID) } as FictionResult.Success).value
        val target = detail.chapters.first { it.title == "c1" }
        val c = runBlocking { src.chapter(GoogleNewsSections.FOR_YOU_ID, target.id) }
        assertTrue("expected Success, got $c", c is FictionResult.Success)
        assertTrue((c as FictionResult.Success).value.plainBody.startsWith("c1"))
    }

    @Test
    fun `the chosen edition reaches the wire`() {
        routes = mapOf("/rss" to ok(rss("x")))
        runBlocking { source(feed).fictionDetail(GoogleNewsSections.TOP_ID) }
        assertTrue(requested.toString(), requested.single().contains("ceid=US:es-419"))
    }

    @Test
    fun `popular lists For you and the followed sections first`() {
        val r = runBlocking { source(feed).popular(1) } as FictionResult.Success
        val ids = r.value.items.map { it.id }
        assertEquals(GoogleNewsSections.FOR_YOU_ID, ids[0])
        assertEquals(GoogleNewsSections.TOP_ID, ids[1])
        assertEquals(GoogleNewsSections.topicFictionId(GoogleNewsTopic.SCIENCE), ids[2])
        assertEquals(GoogleNewsSections.searchFictionId("clima"), ids[3])
        assertEquals("Para ti", r.value.items[0].title)
        // Browse cards need no network.
        assertTrue(requested.isEmpty())
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun rss(vararg titles: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>t</title>""" +
            titles.joinToString("") {
                "<item><title>$it</title><link>https://news.google.com/articles/CBMi$it</link><guid>$it</guid></item>"
            } + "</channel></rss>"

    private object NoOpResolver : ArticleResolver {
        override suspend fun resolve(item: GoogleNewsItem): String? = null
    }
}
