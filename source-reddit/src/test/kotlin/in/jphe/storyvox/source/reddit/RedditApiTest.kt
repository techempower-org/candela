package `in`.jphe.storyvox.source.reddit

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.reddit.net.RedditApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Issue #1492 — OAuth token lifecycle + rate-limit mapping in RedditApi. */
class RedditApiTest {

    private lateinit var server: MockWebServer
    private val tokenMints = AtomicInteger(0)
    private var clock = 0L

    @Before fun setUp() {
        tokenMints.set(0)
        clock = 0L
        server = MockWebServer()
    }

    @After fun tearDown() = server.shutdown()

    /** RedditApi with a controllable clock for token-expiry testing. */
    private fun api(): RedditApi {
        val config = fakeRedditConfig(host = server.url("/").toString())
        return object : RedditApi(testClient(), config) {
            override fun nowMillis(): Long = clock
        }
    }

    @Test fun `bearer is reused until it nears expiry, then re-minted`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.contains("access_token")) {
                    tokenMints.incrementAndGet()
                    MockResponse().setResponseCode(200).setBody(TOKEN_JSON) // expires_in 3600
                } else {
                    MockResponse().setResponseCode(200).setBody(SUBREDDIT_LISTING_JSON)
                }
            }
        }
        val api = api()

        clock = 0L
        api.popularSubreddits()
        api.popularSubreddits() // still fresh → cache hit
        assertEquals(1, tokenMints.get())

        // Expiry = 0 + (3600 - 60) * 1000 = 3_540_000ms. Jump past it.
        clock = 3_600_000L
        api.popularSubreddits()
        assertEquals(2, tokenMints.get())
    }

    @Test fun `429 on an API call maps to RateLimited with retry-after`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return if (path.contains("access_token")) {
                    MockResponse().setResponseCode(200).setBody(TOKEN_JSON)
                } else {
                    MockResponse().setResponseCode(429).setHeader("Retry-After", "30")
                }
            }
        }
        val result = api().popularSubreddits()
        assertTrue("expected RateLimited, got $result", result is FictionResult.RateLimited)
        assertEquals(30.seconds, (result as FictionResult.RateLimited).retryAfter)
    }

    @Test fun `401 on the token mint maps to AuthRequired`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401)
        }
        val result = api().popularSubreddits()
        assertTrue("expected AuthRequired, got $result", result is FictionResult.AuthRequired)
    }

    @Test fun `parseRetryAfter reads whole seconds and rejects the rest`() {
        assertEquals(45.seconds, RedditApi.parseRetryAfter("45"))
        assertEquals(0.seconds, RedditApi.parseRetryAfter(" 0 "))
        assertNull(RedditApi.parseRetryAfter(null))
        assertNull(RedditApi.parseRetryAfter("-1"))
        assertNull(RedditApi.parseRetryAfter("soon"))
        // HTTP-date form is not whole-seconds → null (best-effort, rare).
        assertNull(RedditApi.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }
}
