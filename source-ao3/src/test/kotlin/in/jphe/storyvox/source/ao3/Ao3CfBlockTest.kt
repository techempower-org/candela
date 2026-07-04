package `in`.jphe.storyvox.source.ao3

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.ao3.net.Ao3Api
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1502 — a Cloudflare WAF / firewall *block* page (HTTP 403, "Error 1020:
 * Access denied", "Sorry, you have been blocked") carries NONE of the
 * interactive-challenge markers [Ao3Api.looksLikeCfChallenge] sniffs for, so
 * it used to fall through to the 401/403 -> [FictionResult.AuthRequired] arm.
 * That arm is TERMINAL in ChapterDownloadWorker (FAILED + a sign-in prompt
 * that can never clear a WAF block) where a retryable [FictionResult.NetworkError]
 * would let WorkManager back off and retry. These tests pin the new
 * classification and the end-to-end routing on both network paths
 * ([Ao3Api.tagFeed] via `requestText`, and [Ao3Api.downloadEpub]).
 */
class Ao3CfBlockTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** A representative Cloudflare Error-1020 WAF block page (403, no JS challenge). */
    private fun cfBlockBody(): String =
        """
        <!DOCTYPE html>
        <html lang="en-US">
        <head><title>Attention Required! | Cloudflare</title></head>
        <body>
          <h1>Sorry, you have been blocked</h1>
          <h2>You are unable to access archiveofourown.org</h2>
          <p>Error 1020: Access denied</p>
          <p>Cloudflare Ray ID: 8b0f0c0a0b0c0d0e</p>
          <p>Performance &amp; security by Cloudflare</p>
        </body>
        </html>
        """.trimIndent()

    private fun api(): Ao3Api {
        val host = server.url("/").toString().trimEnd('/')
        val client = OkHttpClient()
        return object : Ao3Api(client, client) {
            override val baseUrl: String get() = host
        }
    }

    // ─── classification (pure) ────────────────────────────────────────────

    @Test
    fun `looksLikeCfBlock matches the 1020 WAF page`() {
        assertTrue(Ao3Api.looksLikeCfBlock(cfBlockBody()))
    }

    @Test
    fun `looksLikeCfBlock matches common block phrasings`() {
        assertTrue(
            "error code: 1020 variant",
            Ao3Api.looksLikeCfBlock("Cloudflare — error code: 1020 Access denied"),
        )
        assertTrue(
            "access denied + ray id pair",
            Ao3Api.looksLikeCfBlock("<p>Access denied</p><p>Cloudflare Ray ID: abc123</p>"),
        )
    }

    @Test
    fun `looksLikeCfBlock rejects real AO3 content and login form`() {
        // Real content has no "cloudflare" token at all.
        assertFalse(
            Ao3Api.looksLikeCfBlock(
                """<ol class="work index group"><li class="work blurb">A fic</li></ol>""",
            ),
        )
        // An expired-session login form is a genuine auth signal, not a WAF block.
        assertFalse(Ao3Api.looksLikeCfBlock("""<form id="new_user" class="new_user">"""))
    }

    @Test
    fun `looksLikeCfBlock does not fire on a mere mention of cloudflare`() {
        // A fic body that quotes the word must not be reclassified as a block.
        assertFalse(
            Ao3Api.looksLikeCfBlock(
                "The sysadmin muttered about Cloudflare outages while sipping coffee.",
            ),
        )
    }

    @Test
    fun `classification split - a WAF block is not an interactive challenge`() {
        // The two sniffs are mutually exclusive on a pure WAF page: the block
        // must not be routed to the WebView challenge resolver.
        assertTrue(Ao3Api.looksLikeCfBlock(cfBlockBody()))
        assertFalse(Ao3Api.looksLikeCfChallenge(cfBlockBody()))
    }

    // ─── end-to-end routing ───────────────────────────────────────────────

    @Test
    fun `tagFeed maps a CF WAF 403 to a retryable NetworkError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody(cfBlockBody()))
        val result = api().tagFeed(tagId = 414093L)
        assertTrue(
            "CF WAF block must be retryable NetworkError, got $result",
            result is FictionResult.NetworkError,
        )
    }

    @Test
    fun `tagFeed still maps a genuine 403 to AuthRequired`() = runTest {
        // A 403 with no Cloudflare block markers is a real auth gate and must
        // stay terminal AuthRequired — the fix must not swallow that.
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("<html><body>Forbidden</body></html>"),
        )
        val result = api().tagFeed(tagId = 414093L)
        assertTrue(
            "plain 403 must stay AuthRequired, got $result",
            result is FictionResult.AuthRequired,
        )
    }

    @Test
    fun `downloadEpub maps a CF WAF 403 to a retryable NetworkError`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody(cfBlockBody()))
        val result = api().downloadEpub(workId = 12345L)
        assertTrue(
            "CF WAF block on EPUB download must be retryable NetworkError, got $result",
            result is FictionResult.NetworkError,
        )
    }

    @Test
    fun `downloadEpub still maps a genuine 403 to AuthRequired`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setBody("<html><body>You must agree</body></html>"),
        )
        val result = api().downloadEpub(workId = 12345L)
        assertTrue(
            "plain 403 on EPUB download must stay AuthRequired, got $result",
            result is FictionResult.AuthRequired,
        )
    }
}
