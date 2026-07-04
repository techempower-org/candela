package `in`.jphe.storyvox.source.notion

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionMode
import `in`.jphe.storyvox.source.notion.net.NotionApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1507 — [NotionPATSource] against the shared
 * [FictionSourceContractTest], configured with an OAuth token
 * (`viaOAuth = true`) so `popular()` routes through the NEW
 * `POST /v1/search` granted-content path. That gives the contract kit's
 * checks — the #585 IO-pin, 401→AuthRequired, 429→RateLimited, and the
 * Cloudflare-challenge detection just added to `NotionApi` — real coverage
 * of the search shape, not just the legacy `databases/{id}/query` path.
 *
 * The [NotionApi] base-URL seam is the fake config's `baseUrl`, pointed at
 * the kit's MockWebServer. A non-blank `apiToken` + `OFFICIAL_PAT` mode is
 * what gates the browse calls open.
 */
class NotionPatContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val config = FakeNotionConfig(baseUrl.trimEnd('/'))
        return NotionPATSource(NotionApi(client, config), config)
    }

    /** With an OAuth token, `popular()` hits `POST /v1/search`. */
    override fun listPathFragment(): String = "search"

    /** A well-formed but empty `/v1/search` listing. */
    override fun happyListBody(): String =
        """{"object":"list","results":[],"has_more":false}"""

    /**
     * #1507 — positive path: a page returned by `/v1/search` (a granted
     * object, e.g. a row of a shared database) maps to one browsable
     * fiction via `NotionPage.toSummary`, stamped with the PAT source id.
     */
    @Test
    fun `oauth popular maps granted search pages to fictions`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path?.contains("/v1/search") == true) {
                    MockResponse().setResponseCode(200).setBody(
                        """
                        {"object":"list","has_more":false,"results":[
                          {"id":"page-1","properties":{
                            "Name":{"type":"title","title":[{"plain_text":"Granted Short"}]}
                          }}
                        ]}
                        """.trimIndent(),
                    )
                } else {
                    MockResponse().setResponseCode(404)
                }
        }
        val result = runBlocking {
            createSource(OkHttpClient(), server.url("/").toString()).popular(1)
        }
        assertTrue("expected Success, got $result", result is FictionResult.Success)
        val items = (result as FictionResult.Success).value.items
        assertEquals(1, items.size)
        assertEquals("Granted Short", items.first().title)
        assertEquals(SourceIds.NOTION_PAT, items.first().sourceId)
    }

    /** A blank apiToken short-circuits to empty without any HTTP — the
     *  "not connected" posture the Browse chip shows before consent. */
    @Test
    fun `no token yields empty popular without hitting the network`() {
        val config = FakeNotionConfig(server.url("/").toString().trimEnd('/'), token = "")
        val src = NotionPATSource(NotionApi(OkHttpClient(), config), config)
        val result = runBlocking { src.popular(1) }
        assertTrue("expected Success(empty), got $result", result is FictionResult.Success)
        assertEquals(0, (result as FictionResult.Success).value.items.size)
    }

    private class FakeNotionConfig(
        baseUrl: String,
        token: String = "contract-oauth-token",
    ) : NotionConfig {
        private val snapshot = NotionConfigState(
            mode = if (token.isBlank()) NotionMode.ANONYMOUS_PUBLIC else NotionMode.OFFICIAL_PAT,
            apiToken = token,
            viaOAuth = token.isNotBlank(),
            baseUrl = baseUrl,
        )
        override val state: Flow<NotionConfigState> = flowOf(snapshot)
        override suspend fun current(): NotionConfigState = snapshot
    }
}
