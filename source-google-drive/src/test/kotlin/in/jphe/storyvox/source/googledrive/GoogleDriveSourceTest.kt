package `in`.jphe.storyvox.source.googledrive

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfig
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfigState
import `in`.jphe.storyvox.source.googledrive.net.GoogleDriveApi
import `in`.jphe.storyvox.source.googledrive.parse.DriveBook
import `in`.jphe.storyvox.source.googledrive.parse.DriveBookReader
import `in`.jphe.storyvox.source.googledrive.parse.DriveChapter
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

    private class FakeConfig(
        private val token: String,
        private val refreshed: String? = null,
    ) : GoogleDriveConfig {
        var refreshCalls = 0
        override val state: Flow<GoogleDriveConfigState> =
            flowOf(GoogleDriveConfigState(accessToken = token))
        override suspend fun current() = GoogleDriveConfigState(accessToken = token)
        override suspend fun refreshAccessToken(): String? {
            refreshCalls++
            return refreshed
        }
    }

    /** Records what it was asked to parse; returns canned chapters. */
    private class FakeReader : DriveBookReader {
        var epubCalls = 0
        var pdfCalls = 0
        var lastBytes: ByteArray? = null
        override suspend fun readEpub(bytes: ByteArray): DriveBook {
            epubCalls++
            lastBytes = bytes
            return DriveBook(
                author = "Ada Lovelace",
                chapters = listOf(
                    DriveChapter(title = "Opening", plainBody = "First chapter."),
                    DriveChapter(title = "", plainBody = "Second chapter."),
                ),
            )
        }
        override suspend fun readPdf(fileId: String, bytes: ByteArray): DriveBook {
            pdfCalls++
            lastBytes = bytes
            return DriveBook(
                author = "",
                chapters = listOf(DriveChapter(title = "Pages 1–5", plainBody = "Para one.\n\nPara two.")),
            )
        }
    }

    private fun source(
        token: String = "tok",
        config: FakeConfig = FakeConfig(token),
        reader: DriveBookReader = FakeReader(),
    ): GoogleDriveSource {
        val host = server.url("/").toString().trimEnd('/')
        return GoogleDriveSource(
            object : GoogleDriveApi(OkHttpClient()) {
                override val baseUrl: String get() = host
            },
            config,
            reader,
        )
    }

    /** fileMeta for [mime], `alt=media` serving [media]. */
    private fun serveFile(id: String, name: String, mime: String, media: String = "BYTES") {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("alt=media") -> MockResponse().setResponseCode(200).setBody(media)
                    else -> MockResponse().setResponseCode(200).setBody(
                        """{"id":"$id","name":"$name","mimeType":"$mime","modifiedTime":"2026-09-01T00:00:00Z"}""",
                    )
                }
            }
        }
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

    // ─── #1677 — EPUB / PDF, refresh, listing ───────────────────────────

    @Test fun `popular lists PDFs and EPUBs with kind labels and trimmed titles`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(200).setBody(
                    """
                    {"files":[
                      {"id":"p1","name":"Syllabus.pdf","mimeType":"application/pdf"},
                      {"id":"e1","name":"Moby Dick.epub","mimeType":"application/epub+zip"},
                      {"id":"x1","name":"photo.png","mimeType":"image/png"}
                    ]}
                    """.trimIndent(),
                )
        }
        val items = (runBlocking { source().popular(1) } as FictionResult.Success).value.items
        assertEquals(listOf("Syllabus", "Moby Dick"), items.map { it.title })
        assertEquals(listOf("PDF", "EPUB"), items.map { it.description })
        val req = server.takeRequest()
        assertTrue("q must ask for PDFs", req.path!!.contains("application%2Fpdf"))
        assertTrue("q must ask for EPUBs", req.path!!.contains("application%2Fepub%2Bzip"))
    }

    @Test fun `epub detail lists one chapter per spine item with the book author`() {
        serveFile("e1", "Moby Dick.epub", "application/epub+zip")
        val reader = FakeReader()
        val detail = (runBlocking { source(reader = reader).fictionDetail("google-drive:e1") } as FictionResult.Success).value
        assertEquals(2, detail.chapters.size)
        assertEquals("Opening", detail.chapters[0].title)
        assertEquals("Part 2", detail.chapters[1].title)
        assertEquals("google-drive:e1::c1", detail.chapters[1].id)
        assertEquals("Ada Lovelace", detail.summary.author)
        assertEquals(2, detail.summary.chapterCount)
        assertEquals("BYTES", String(reader.lastBytes!!))
    }

    @Test fun `epub chapter reads the indexed spine item and memoises the parse`() {
        serveFile("e1", "Moby Dick.epub", "application/epub+zip")
        val reader = FakeReader()
        val src = source(reader = reader)
        val second = runBlocking {
            src.fictionDetail("google-drive:e1")
            src.chapter("google-drive:e1", "google-drive:e1::c1")
        }
        assertEquals("Second chapter.", (second as FictionResult.Success).value.plainBody)
        assertEquals(1, reader.epubCalls)
        val downloads = (0 until server.requestCount).map { server.takeRequest().path.orEmpty() }
            .count { it.contains("alt=media") }
        assertEquals("one download serves detail + chapter", 1, downloads)
    }

    @Test fun `pdf chapter carries paragraph html and plain text`() {
        serveFile("p1", "Syllabus.pdf", "application/pdf")
        val reader = FakeReader()
        val content = (runBlocking { source(reader = reader).chapter("google-drive:p1", "google-drive:p1::c0") } as FictionResult.Success).value
        assertEquals("Para one.\n\nPara two.", content.plainBody)
        assertEquals("<p>Para one.</p><p>Para two.</p>", content.htmlBody)
        assertEquals(1, reader.pdfCalls)
    }

    @Test fun `out-of-range chapter index is NotFound`() {
        serveFile("e1", "Moby Dick.epub", "application/epub+zip")
        val result = runBlocking { source().chapter("google-drive:e1", "google-drive:e1::c9") }
        assertTrue("got $result", result is FictionResult.NotFound)
    }

    @Test fun `unreadable mime type is NotFound, not a crash`() {
        serveFile("x1", "photo.png", "image/png")
        val result = runBlocking { source().fictionDetail("google-drive:x1") }
        assertTrue("got $result", result is FictionResult.NotFound)
    }

    @Test fun `401 triggers one refresh and a retry with the new token`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.getHeader("Authorization") == "Bearer fresh") {
                    MockResponse().setResponseCode(200).setBody("""{"files":[]}""")
                } else {
                    MockResponse().setResponseCode(401)
                }
        }
        val config = FakeConfig(token = "stale", refreshed = "fresh")
        val result = runBlocking { source(config = config).popular(1) }
        assertTrue("got $result", result is FictionResult.Success)
        assertEquals(1, config.refreshCalls)
        assertEquals(2, server.requestCount)
    }

    @Test fun `401 with no refresh possible stays AuthRequired`() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401)
        }
        val config = FakeConfig(token = "stale", refreshed = null)
        val result = runBlocking { source(config = config).popular(1) }
        assertTrue("got $result", result is FictionResult.AuthRequired)
        assertEquals(1, server.requestCount)
    }

    @Test fun `google doc export strips the BOM and CRLFs`() {
        assertEquals("a\nb", normalizeTextBody("﻿a\r\nb", "text/plain"))
    }

    @Test fun `chapter index parsing tolerates malformed ids`() {
        assertEquals(3, parseChapterIndex("google-drive:x::c3"))
        assertEquals(0, parseChapterIndex("google-drive:x"))
        assertEquals(0, parseChapterIndex("google-drive:x::cfoo"))
    }

    @Test fun `display title keeps Google Doc names verbatim`() {
        assertEquals("notes.pdf", displayTitle("notes.pdf", "application/vnd.google-apps.document"))
        assertEquals("notes", displayTitle("notes.PDF", "application/pdf"))
        assertEquals(".pdf", displayTitle(".pdf", "application/pdf"))
    }

    @Test fun `blank search term returns empty without hitting network`() {
        val result = runBlocking { source().search(SearchQuery(term = "   ")) }
        assertTrue(result is FictionResult.Success)
        assertTrue((result as FictionResult.Success).value.items.isEmpty())
        assertEquals(0, server.requestCount)
    }
}
