package `in`.jphe.storyvox.testkit.source

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
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
import java.util.Collections

/**
 * Contract every HTTP-backed [FictionSource] must satisfy. Subclass in your
 * source module's unit tests, implement the three hooks, and the tribal
 * gotchas become executable checks:
 *
 *  - network work leaves the caller thread (#585 Dispatchers.IO pin) — checked
 *    for EVERY request the exercised call issues, not just the last one, so a
 *    multi-request source (list fetch + per-item fetches) with one un-pinned
 *    call still fails
 *  - 401 -> AuthRequired, 429 -> RateLimited — never raw exceptions
 *  - Cloudflare challenge bodies are detected and surface as
 *    [FictionResult.Cloudflare] or [FictionResult.NetworkError] — never as
 *    content, and never as AuthRequired (a CF 403 is not a login gate)
 *  - [listPathFragment] actually matches a requested path (#1523) — a wrong
 *    fragment routes the happy body nowhere and every request 404s, yet the
 *    IO-pin check above still passes on those 404s (a silent false-green)
 *  - paging & blank-query sanity
 *
 * Search-only sources set [exercisesPopular] = false; the checks then run
 * through [FictionSource.search] instead of being skipped, so coverage is
 * preserved. Non-HTTP sources (local files) skip this kit entirely; see
 * docs/CONTRIBUTING-SOURCES.md.
 */
abstract class FictionSourceContractTest {

    protected lateinit var server: MockWebServer
    // Appended from MockWebServer's dispatcher threads while the JUnit thread
    // reads it — must be synchronized or a concurrent-request source races.
    private val requestThreads: MutableList<String> =
        Collections.synchronizedList(mutableListOf())
    // #1523 — every request path the exercised call issued, so the happy-path
    // check can prove listPathFragment() actually matched a requested path. A
    // wrong fragment routes the happy body NOWHERE (every request falls through
    // to the 404 arm), yet the IO-pin check still passes on those 404s — a
    // silent false-green. Same synchronized-append discipline as requestThreads.
    private val requestPaths: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    /** Build YOUR source pointed at [baseUrl] with [client]. */
    protected abstract fun createSource(client: OkHttpClient, baseUrl: String): FictionSource

    /** A happy-path response body for a list endpoint of your API (popular/latest). */
    protected abstract fun happyListBody(): String

    /** Path fragment your list endpoint hits (used to route the happy body). */
    protected abstract fun listPathFragment(): String

    /** Override to false for search-only sources: the contract checks below
     *  then exercise [FictionSource.search] instead of [FictionSource.popular]. */
    protected open val exercisesPopular: Boolean = true

    @Before fun startServer() {
        requestThreads.clear()
        requestPaths.clear()
        ClientThreadProbe.clientThreads.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestThreads += Thread.currentThread().name
                requestPaths += request.path.orEmpty()
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
     * OkHttp actually executes EACH call on (the IO-pin probe below). Sources
     * see a plain [OkHttpClient]; the interceptor is invisible to them.
     */
    private fun source(): FictionSource = createSource(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                ClientThreadProbe.clientThreads += Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build(),
        server.url("/").toString(),
    )

    /** The list call the contract checks exercise: popular() when the source
     *  supports it, otherwise search() — so search-only sources keep coverage. */
    private suspend fun exerciseList(src: FictionSource): FictionResult<*> =
        if (exercisesPopular) src.popular(1)
        else src.search(SearchQuery(term = "contract probe"))

    @Test fun `network work leaves the caller thread`() {
        val caller = Thread.currentThread().name
        runBlocking { exerciseList(source()) }
        assertTrue(
            "no request reached the probe — the exercised call issued no HTTP",
            ClientThreadProbe.clientThreads.isNotEmpty(),
        )
        // A source missing withContext(Dispatchers.IO) executes OkHttp's blocking
        // call on the runBlocking caller thread (NetworkOnMainThread on Android).
        // Every recorded client thread must differ from the caller — checking
        // only the last one lets a multi-request source hide one un-pinned call
        // behind later pinned ones (#585's copy-paste failure mode).
        val offenders = ClientThreadProbe.clientThreads.filter { it == caller }
        assertTrue(
            "${offenders.size} request(s) executed on the caller thread — " +
                "missing withContext(Dispatchers.IO) (#585)",
            offenders.isEmpty(),
        )
    }

    @Test fun `listPathFragment matches a requested path (happy body is served)`() {
        runBlocking { exerciseList(source()) }
        val fragment = listPathFragment()
        val paths = synchronized(requestPaths) { requestPaths.toList() }
        // A wrong listPathFragment() sends every request to the default router's
        // 404 arm, so the happy body is served NOWHERE — but the #585 IO-pin
        // check above still passes (a request DID execute off the caller thread,
        // it just 404'd). That silently false-greens a mis-routed fragment (it
        // bit the first non-id-in-path source, #1523). Asserting the fragment
        // matched a real requested path makes the mistake fail loudly instead.
        assertTrue(
            "listPathFragment(\"$fragment\") matched none of the requested paths " +
                "$paths — the happy list body was served nowhere (every request fell " +
                "through to the 404 arm). Point listPathFragment() at a substring your " +
                "popular()/search() endpoint actually requests (#1523).",
            paths.any { it.contains(fragment) },
        )
    }

    @Test fun `returned fiction ids carry the source's own id prefix`() {
        // #1564 — a FictionSource MUST return ids shaped "<pluginId>:<localId>".
        // FictionSourceIdResolver routes a fiction to its owning source by the
        // id's colon prefix and DEFAULTS a colon-less id to Royal Road — so a
        // source returning bare ids has its fictions silently misrouted (their
        // detail/reader never open). Compiles clean, breaks only at runtime;
        // this makes it fail honestly in the kit (matching the #1523 philosophy).
        val src = source()
        val result = runBlocking { exerciseList(src) }
        val page = (result as? FictionResult.Success<*>)?.value as? ListPage<*>
        // The happy-body-served + parsed guarantees live in the checks above;
        // here we only assert the SHAPE of whatever ids came back.
        val ids = page?.items?.filterIsInstance<FictionSummary>()?.map { it.id }.orEmpty()
        val prefix = "${src.id}:"
        val offenders = ids.filterNot { it.startsWith(prefix) }
        assertTrue(
            "FictionSummary ids must be \"<pluginId>:<localId>\" — this source's id is " +
                "\"${src.id}\", so every returned id must start with \"$prefix\". A colon-less " +
                "or wrong-prefix id silently routes to Royal Road at runtime " +
                "(FictionSourceIdResolver), so detail/reader never open (#1564). Offenders: $offenders",
            offenders.isEmpty(),
        )
    }

    @Test fun `401 maps to AuthRequired, not an exception`() {
        server.dispatcher = constant(MockResponse().setResponseCode(401))
        val result = runBlocking { exerciseList(source()) }
        assertTrue("expected AuthRequired, got $result", result is FictionResult.AuthRequired)
    }

    @Test fun `429 maps to RateLimited`() {
        server.dispatcher = constant(MockResponse().setResponseCode(429))
        val result = runBlocking { exerciseList(source()) }
        assertTrue("expected RateLimited, got $result", result is FictionResult.RateLimited)
    }

    @Test fun `cloudflare challenge body is detected, never stored`() {
        server.dispatcher = constant(
            MockResponse().setResponseCode(403)
                .setHeader("Server", "cloudflare")
                .setBody(CF_CHALLENGE_BODY),
        )
        val result = runBlocking { exerciseList(source()) }
        // AuthRequired would ALSO be a Failure — but a CF challenge is not a
        // login gate, and mapping it to auth sends users to a sign-in prompt
        // that cannot succeed. Only the two honest shapes pass.
        assertTrue(
            "CF challenge must map to Cloudflare/NetworkError, got $result",
            result is FictionResult.Cloudflare || result is FictionResult.NetworkError,
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
            requestPaths += request.path.orEmpty()
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
    /** Thread name of every client-side request execution, in order. */
    val clientThreads: MutableList<String> =
        Collections.synchronizedList(mutableListOf())
}
