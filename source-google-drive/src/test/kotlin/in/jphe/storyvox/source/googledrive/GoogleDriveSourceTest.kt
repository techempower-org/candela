package `in`.jphe.storyvox.source.googledrive

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfig
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfigState
import `in`.jphe.storyvox.source.googledrive.net.GoogleDriveApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

/**
 * Issue #1496 — unit coverage for the Drive folder-as-library mapping, the
 * connect gate, and the Google-Docs-export vs blob-download branch.
 */
class GoogleDriveSourceTest {

    private lateinit var server: MockWebServer

    private class FakeConfig(private val token: String) : GoogleDriveConfig {
        override val state: Flow<GoogleDriveConfigState> =
            flowOf(GoogleDriveConfigState(accessToken = token))
        override suspend fun current() = GoogleDriveConfigState(accessToken = token)
    }

    private fun source(token: String = "tok"): GoogleDriveSource {
        val host = server.url("/").toString().trimEnd('/')
        return GoogleDriveSource(
            object : GoogleDriveApi(OkHttpClient()) {
                override val baseUrl: String get() = host
            },
            FakeConfig(token),
        )
    }

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    @Test fun `blank token short-circuits browse to AuthRequired`() {
        val result = runBlocking { source(token = "").popular(1) }
        assertTrue("expected AuthRequired, got $result", result is FictionResult.AuthRequired)
    }

    @Test fun `popular maps files to summaries and skips folders`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"files":[
                      {"id":"doc1","name":"A Tale","mimeType":"application/vnd.google-apps.document"},
                      {"id":"fold","name":"A Folder","mimeType":"application/vnd.google-apps.folder"},
                      {"id":"txt1","name":"notes.txt","mimeType":"text/plain"}
                    ]}
                    """.trimIndent(),
                )
        }
        val page = runBlocking { source().popular(1) }
        assertTrue(page is FictionResult.Success)
        val items = (page as FictionResult.Success).value.items
        // Folder filtered out; two readable files mapped.
        assertEquals(listOf("google-drive:doc1", "google-drive:txt1"), items.map { it.id })
        assertEquals("A Tale", items[0].title)
        assertEquals("Google Drive", items[0].author)
    }

    @Test fun `popular sends drive-file-scoped query and bearer token`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
        }
        runBlocking { source(token = "secret-tok").popular(1) }
        val req = server.takeRequest()
        assertEquals("Bearer secret-tok", req.getHeader("Authorization"))
        assertTrue("q must filter Google Docs", req.path!!.contains("vnd.google-apps.document"))
        assertTrue("q must exclude trashed", req.path!!.contains("trashed"))
    }

    @Test fun `chapter exports a Google Doc as plain text`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/export") ->
                        MockResponse().setResponseCode(200).setBody("Once upon a time.")
                    else -> // fileMeta
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"doc1","name":"A Tale","mimeType":"application/vnd.google-apps.document"}""",
                        )
                }
            }
        }
        val result = runBlocking { source().chapter("google-drive:doc1", "google-drive:doc1::c0") }
        assertTrue(result is FictionResult.Success)
        val content = (result as FictionResult.Success).value
        assertEquals("Once upon a time.", content.plainBody)
        assertEquals("A Tale", content.info.title)
    }

    @Test fun `chapter downloads a text file via alt media`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("alt=media") ->
                        MockResponse().setResponseCode(200).setBody("plain body bytes")
                    else -> // fileMeta
                        MockResponse().setResponseCode(200).setBody(
                            """{"id":"txt1","name":"notes.txt","mimeType":"text/plain"}""",
                        )
                }
            }
        }
        val result = runBlocking { source().chapter("google-drive:txt1", "google-drive:txt1::c0") }
        assertTrue(result is FictionResult.Success)
        assertEquals("plain body bytes", (result as FictionResult.Success).value.plainBody)
    }

    @Test fun `fictionId round-trips`() {
        assertEquals("google-drive:abc123", fictionIdFor("abc123"))
        assertEquals("abc123", parseFileId("google-drive:abc123"))
        assertNull(parseFileId("royalroad:999"))
        assertNull(parseFileId("google-drive:"))
    }

    @Test fun `escapeQ neutralizes quote injection in search terms`() {
        assertEquals("o\\'brien", escapeQ("o'brien"))
    }

    @Test fun `blank search term returns empty without hitting network`() {
        val result = runBlocking { source().search(SearchQuery(term = "   ")) }
        assertTrue(result is FictionResult.Success)
        assertTrue((result as FictionResult.Success).value.items.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
