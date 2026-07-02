package `in`.jphe.storyvox.testkit.source

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Contract every HTTP-backed [FictionSource] must satisfy. Subclass in your
 * source module's unit tests, implement the three hooks, and the tribal
 * gotchas become executable checks:
 *
 *  - network work leaves the caller thread (#585 Dispatchers.IO pin)
 *  - 401/403 -> AuthRequired, 429 -> RateLimited — never raw exceptions
 *  - Cloudflare challenge bodies are detected, never returned as content
 *  - paging & blank-query sanity
 *
 * Non-HTTP sources (local files) skip this kit; see docs/CONTRIBUTING-SOURCES.md.
 */
abstract class FictionSourceContractTest {

    protected lateinit var server: MockWebServer
    private val requestThreads = mutableListOf<String>()

    /** Build YOUR source pointed at [baseUrl] with [client]. */
    protected abstract fun createSource(client: OkHttpClient, baseUrl: String): FictionSource

    /** A happy-path response body for a list endpoint of your API (popular/latest). */
    protected abstract fun happyListBody(): String

    /** Path fragment your list endpoint hits (used to route the happy body). */
    protected abstract fun listPathFragment(): String

    /** Override if your source cannot serve popular() (e.g. search-only). */
    protected open val exercisesPopular: Boolean = true

    @Before fun startServer() {
        requestThreads.clear()
        ClientThreadProbe.lastClientThread = null
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestThreads += Thread.currentThread().name
                return route(request)
            }
        }
        server.start()
    }

    @After fun stopServer() { server.shutdown() }

    /** Default router; override for multi-endpoint fixtures. */
    protected open fun route(request: RecordedRequest): MockResponse =
        if (request.path?.contains(listPathFragment()) == true)
            MockResponse().setResponseCode(200).setBody(happyListBody())
        else MockResponse().setResponseCode(404)

    /**
     * The source under test, built against a client that records which thread
     * OkHttp actually executes the call on (the IO-pin probe below). Sources
     * see a plain [OkHttpClient]; the interceptor is invisible to them.
     */
    private fun source(): FictionSource = createSource(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                ClientThreadProbe.lastClientThread = Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build(),
        server.url("/").toString(),
    )

    @Test fun `network work leaves the caller thread`() {
        if (!exercisesPopular) return
        val caller = Thread.currentThread().name
        runBlocking { source().popular(1) }
        assertTrue("no request reached the server", requestThreads.isNotEmpty())
        // MockWebServer serves on its own thread; the IO-pin check is on the CLIENT:
        // a source missing withContext(Dispatchers.IO) executes OkHttp's blocking call
        // on the runBlocking caller thread and Android would throw NetworkOnMainThread.
        // The interceptor above records the thread OkHttp actually ran on; if that is
        // the runBlocking caller thread, the source never left it.
        val onCaller = ClientThreadProbe.lastClientThread == caller
        assertTrue("HTTP executed on caller thread — missing withContext(Dispatchers.IO) (#585)", !onCaller)
    }

    @Test fun `401 maps to AuthRequired, not an exception`() {
        if (!exercisesPopular) return
        server.dispatcher = constant(MockResponse().setResponseCode(401))
        val result = runBlocking { source().popular(1) }
        assertTrue("expected AuthRequired, got $result", result is FictionResult.AuthRequired)
    }

    @Test fun `429 maps to RateLimited`() {
        if (!exercisesPopular) return
        server.dispatcher = constant(MockResponse().setResponseCode(429))
        val result = runBlocking { source().popular(1) }
        assertTrue("expected RateLimited, got $result", result is FictionResult.RateLimited)
    }

    @Test fun `cloudflare challenge body is detected, never stored`() {
        if (!exercisesPopular) return
        server.dispatcher = constant(
            MockResponse().setResponseCode(403)
                .setHeader("Server", "cloudflare")
                .setBody(CF_CHALLENGE_BODY),
        )
        val result = runBlocking { source().popular(1) }
        assertTrue(
            "CF challenge must map to Cloudflare/NetworkError failure, got $result",
            result is FictionResult.Failure,
        )
        if (result is FictionResult.Success<*>) fail("challenge page surfaced as content")
    }

    @Test fun `blank search does not crash`() {
        val result = runBlocking { source().search(SearchQuery(term = "")) }
        // Success(empty) or a typed Failure are both acceptable; throwing is not.
        assertNotEquals(null, result)
    }

    private fun constant(r: MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            requestThreads += Thread.currentThread().name
            return r
        }
    }

    companion object {
        /** Minimal real-shaped Cloudflare interstitial. */
        const val CF_CHALLENGE_BODY: String =
            "<!DOCTYPE html><html><head><title>Just a moment...</title></head>" +
                "<body><div id=\"challenge-running\">Checking your browser</div>" +
                "<form id=\"challenge-form\" action=\"/cdn-cgi/challenge\"></form></body></html>"
    }
}

/** Interceptor probe used by the thread-pin check. Sources don't touch this. */
object ClientThreadProbe {
    @Volatile var lastClientThread: String? = null
}
