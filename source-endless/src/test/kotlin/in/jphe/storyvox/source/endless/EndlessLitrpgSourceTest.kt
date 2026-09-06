package `in`.jphe.storyvox.source.endless

import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.endless.net.EndlessLitrpgApi
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Maps real captured daemon payloads into the core-data models. Every
 * fixture here is a response the live daemon actually served — see
 * [EndlessFixtures] for why that matters.
 */
class EndlessLitrpgSourceTest {

    private lateinit var server: MockWebServer

    /** Body `/api/chapters` serves. Mutable so the revision-token tests can
     *  simulate a re-render / a new chapter without a second server. */
    private var indexBody: String = EndlessFixtures.CHAPTER_INDEX

    @Before fun start() {
        indexBody = EndlessFixtures.CHAPTER_INDEX
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.startsWith("/api/story") ->
                        ok(EndlessFixtures.STORY)
                    path.startsWith("/api/chapters?") ->
                        ok(indexBody)
                    path == "/api/chapters/5" ->
                        ok(EndlessFixtures.CHAPTER_WITH_AUDIO)
                    path == "/api/chapters/1" ->
                        ok(EndlessFixtures.CHAPTER_WITHOUT_AUDIO)
                    else -> MockResponse().setResponseCode(404)
                        .setBody("""{"error":"store: chapter not found"}""")
                }
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun source(host: String = server.url("/").toString().trimEnd('/')) =
        EndlessLitrpgSource(EndlessLitrpgApi(OkHttpClient(), FakeEndlessConfig(host)))

    // ─── browse ────────────────────────────────────────────────────────

    @Test fun `popular returns the single serial with a prefixed id`() {
        val page = (runBlocking { source().popular(1) } as FictionResult.Success).value
        assertEquals(1, page.items.size)
        val serial = page.items.single()
        assertEquals("endless:serial", serial.id)
        assertEquals("endless", serial.sourceId)
        assertEquals("The Ashen Ledger", serial.title)
        assertEquals(FictionStatus.ONGOING, serial.status)
        // hasNext must be false or the paginator asks forever for a
        // one-item list.
        assertEquals(false, page.hasNext)
    }

    @Test fun `popular past page one is empty so pagination terminates`() {
        val page = (runBlocking { source().popular(2) } as FictionResult.Success).value
        assertTrue(page.items.isEmpty())
        assertEquals(false, page.hasNext)
    }

    @Test fun `latestUpdates mirrors popular`() {
        val a = (runBlocking { source().popular(1) } as FictionResult.Success).value
        val b = (runBlocking { source().latestUpdates(1) } as FictionResult.Success).value
        assertEquals(a.items.map { it.id }, b.items.map { it.id })
    }

    // ─── search ────────────────────────────────────────────────────────

    @Test fun `search matches the story title`() {
        val page = search("ashen")
        assertEquals(listOf("endless:serial"), page.items.map { it.id })
    }

    @Test fun `search matches the protagonist`() {
        assertEquals(1, search("Kaelen").items.size)
    }

    @Test fun `search falls back to chapter titles`() {
        // "Blackwater" appears only in chapter 5's title, not in any story
        // metadata field — so a hit here proves the index fallback ran.
        assertEquals(1, search("Blackwater").items.size)
    }

    @Test fun `search with no match is empty, not the serial`() {
        assertTrue(search("zzzz-no-such-term").items.isEmpty())
    }

    @Test fun `blank search returns the serial rather than reading as no results`() {
        assertEquals(1, search("").items.size)
    }

    private fun search(term: String): ListPage<FictionSummary> =
        (runBlocking { source().search(SearchQuery(term = term)) } as FictionResult.Success).value

    // ─── detail ────────────────────────────────────────────────────────

    @Test fun `fictionDetail orders chapters by number regardless of arrival order`() {
        // The fixture deliberately returns chapter 5 before chapter 1.
        val detail = detail()
        assertEquals(listOf(1, 5), detail.chapters.map { it.sourceChapterId.toInt() })
        assertEquals(listOf(0, 1), detail.chapters.map { it.index })
    }

    @Test fun `fictionDetail chapter ids are fiction-scoped with a double colon`() {
        assertEquals(
            listOf("endless:serial::1", "endless:serial::5"),
            detail().chapters.map { it.id },
        )
    }

    @Test fun `fictionDetail carries audioUrl at TOC time only where audio exists`() {
        val byNumber = detail().chapters.associateBy { it.sourceChapterId }
        // Chapter 1's audio was invalidated for a re-render, so the daemon
        // nulls mp3_url — the client must NOT invent a URL from the number.
        assertNull(
            "a chapter without rendered audio must have no audioUrl",
            byNumber.getValue("1").audioUrl,
        )
        assertNotNull(byNumber.getValue("5").audioUrl)
    }

    @Test fun `fictionDetail sums word counts`() {
        assertEquals(1479L + 1886L, detail().wordCount)
    }

    private fun detail(): FictionDetail =
        (runBlocking { source().fictionDetail("endless:serial") } as FictionResult.Success).value

    // ─── chapter ───────────────────────────────────────────────────────

    @Test fun `chapter with audio serves the mp3 rehosted on the configured authority`() {
        val content = chapter("endless:serial::5")
        val audio = requireNotNull(content.audioUrl)
        // The daemon reports its own bound address (192.0.2.129:8093). The
        // client must re-host that path on the authority the user actually
        // configured, or media bypasses the configured host entirely.
        assertTrue(
            "expected the media URL re-hosted on the configured authority, got $audio",
            audio.startsWith(server.url("/").toString().trimEnd('/')),
        )
        assertTrue("expected the daemon's own path preserved", audio.endsWith("/media/0005.mp3"))
        // info and content must agree — the reader reads one, playback the other.
        assertEquals(audio, content.info.audioUrl)
    }

    @Test fun `chapter without rendered audio falls back to a text chapter`() {
        val content = chapter("endless:serial::1")
        assertNull(
            "no audio rendered yet must mean audioUrl == null so TTS handles it",
            content.audioUrl,
        )
        // ...and the prose must still be there, or the chapter is unreadable
        // as well as unplayable.
        assertTrue(content.plainBody.isNotBlank())
        assertTrue(content.htmlBody.isNotBlank())
    }

    @Test fun `a stale manifest is not mistaken for evidence of audio`() {
        // Chapter 1 has has_audio=false and null media URLs, but the daemon
        // still serves the manifest from its previous render. Reading
        // manifest presence as "there is audio" would produce a 404 in
        // ExoPlayer instead of a readable text chapter.
        assertNull(chapter("endless:serial::1").audioUrl)
    }

    @Test fun `chapter body strips speaker tags so TTS does not read them aloud`() {
        val plain = chapter("endless:serial::5").plainBody
        assertTrue(
            "speaker tags must not survive into the TTS body: $plain",
            !plain.contains("[narrator]") && !plain.contains("[SYSTEM]") && !plain.contains("[Sera]"),
        )
        assertTrue(plain.contains("The rain in Oakhaven did not wash things clean."))
    }

    @Test fun `chapter body drops the markdown heading so the title is not doubled`() {
        val content = chapter("endless:serial::5")
        assertEquals("The Mill at Blackwater", content.info.title)
        assertTrue(
            "the '# Chapter N' heading must not repeat inside the body",
            !content.plainBody.contains("# Chapter"),
        )
    }

    @Test fun `an unparseable chapter id is NotFound, not a crash`() {
        val result = runBlocking { source().chapter("endless:serial", "not-a-chapter") }
        assertTrue("expected NotFound, got $result", result is FictionResult.NotFound)
    }

    @Test fun `a chapter the daemon does not have is NotFound`() {
        val result = runBlocking { source().chapter("endless:serial", "endless:serial::99") }
        assertTrue("expected NotFound, got $result", result is FictionResult.NotFound)
    }

    private fun chapter(chapterId: String): ChapterContent =
        (
            runBlocking {
                source().chapter("endless:serial", chapterId)
            } as FictionResult.Success
            ).value

    // ─── polling ───────────────────────────────────────────────────────

    @Test fun `latestRevisionToken is stable when nothing has changed`() {
        // The whole value of the hook: an unchanged serial must yield the
        // same token twice, or the worker never skips anything.
        assertEquals(token(), token())
    }

    @Test fun `latestRevisionToken reports the chapter count for legibility`() {
        assertTrue("expected a leading chapter count, got ${token()}", token()!!.startsWith("2:"))
    }

    /**
     * The regression that motivated rewriting this method — using the
     * **real** live-daemon index from either side of the issue-#15
     * re-render, not a synthetic mutation.
     *
     * Chapters 3 and 4 were re-rendered with the correct narrator voice
     * (ch3 `duration_ms` 1,064,350 → 804,975; ch4 856,300 → 637,151);
     * chapters 1, 2 and 5 are byte-identical across the pair. Because the
     * chapter count and the story prompt are both unchanged, the original
     * `latest_chapter:prompt_hash` token was identical before and after —
     * so the poll worker would have skipped `refreshDetail` and left the
     * reader on the wrong-narrator audio indefinitely.
     */
    @Test fun `latestRevisionToken changes across the real issue-15 re-render`() {
        reindex(EndlessFixtures.INDEX_BEFORE_RERENDER)
        val before = requireNotNull(token())
        reindex(EndlessFixtures.INDEX_AFTER_RERENDER)
        val after = requireNotNull(token())

        assertNotEquals(
            "a re-render must mint a fresh token or the reader keeps superseded audio",
            before,
            after,
        )
        // The crux of why the old token failed: the chapter COUNT is
        // identical on both sides, so the count prefix alone cannot
        // distinguish them — only the digest of the per-chapter render
        // output can. Asserting the prefix matches while the whole token
        // differs pins exactly that.
        assertEquals("5:", before.substringBefore(':') + ":")
        assertEquals(before.substringBefore(':'), after.substringBefore(':'))
        assertNotEquals(before.substringAfter(':'), after.substringAfter(':'))
    }

    @Test fun `latestRevisionToken is unchanged when chapters 1, 2 and 5 alone are re-served`() {
        // Control for the test above: the same index served twice must
        // agree, so the inequality there is attributable to ch3/ch4 and
        // not to a token that simply never repeats.
        reindex(EndlessFixtures.INDEX_AFTER_RERENDER)
        val first = token()
        reindex(EndlessFixtures.INDEX_AFTER_RERENDER)
        assertEquals(first, token())
    }

    @Test fun `latestRevisionToken changes when a chapter gains audio`() {
        val before = token()
        reindex(
            EndlessFixtures.CHAPTER_INDEX
                .replace(""""has_audio":false""", """"has_audio":true""")
                .replace(""""mp3_url":null""", """"mp3_url":"http://192.0.2.129:8093/media/0001.mp3""""),
        )
        assertNotEquals(before, token())
    }

    @Test fun `latestRevisionToken changes when a new chapter arrives`() {
        val before = token()
        reindex(bigIndex(chapters = 3))
        assertNotEquals(before, token())
    }

    @Test fun `latestRevisionToken ignores the order the daemon lists chapters in`() {
        // The index is sorted before digesting, so a reordered response is
        // not mistaken for changed content — otherwise the worker would
        // re-fetch the whole detail on every poll.
        val before = token()
        val reversed = """
            [{"number":1,"title":"Collecting the Divine Shard","duration_ms":452729,
              "has_audio":false,"words":1479,"state_dirty":false,
              "pcm_url":null,"mp3_url":null,"total_bytes":null},
             {"number":5,"title":"The Mill at Blackwater","duration_ms":767800,
              "has_audio":true,"words":1886,"state_dirty":false,
              "pcm_url":"http://192.0.2.129:8093/media/0005.pcm",
              "mp3_url":"http://192.0.2.129:8093/media/0005.mp3",
              "total_bytes":24569600}]
        """
        reindex(reversed)
        assertEquals(before, token())
    }

    @Test fun `latestRevisionToken is fixed-width so an endless serial cannot grow it`() {
        // Persisted per fiction; a concatenation of every entry would grow
        // without bound on a serial that never ends.
        val short = token()!!
        reindex(bigIndex(chapters = 400))
        val long = token()!!
        assertEquals(
            "digest width must not depend on chapter count",
            short.substringAfter(':').length,
            long.substringAfter(':').length,
        )
    }

    @Test fun `latestRevisionToken surfaces a fetch failure rather than a stale token`() {
        // Returning a token we couldn't verify would tell the worker
        // "nothing changed" on every network blip.
        val result = runBlocking { source(host = "http://example.com").latestRevisionToken("x") }
        assertTrue("expected a Failure, got $result", result is FictionResult.Failure)
    }

    private fun token(): String? =
        (
            runBlocking {
                source().latestRevisionToken("endless:serial")
            } as FictionResult.Success
            ).value

    /** Swap the body `/api/chapters` serves, leaving other routes intact. */
    private fun reindex(body: String) {
        indexBody = body
    }

    private fun bigIndex(chapters: Int): String =
        (1..chapters).joinToString(prefix = "[", postfix = "]", separator = ",") { n ->
            """{"number":$n,"title":"Chapter $n","duration_ms":${n * 1000},
                "has_audio":true,"words":100,"state_dirty":false,
                "pcm_url":null,"mp3_url":null,"total_bytes":${n * 32000}}"""
        }

    // ─── unconfigured / rejected host ──────────────────────────────────

    @Test fun `an unconfigured host reports a failure instead of pretending to be empty`() {
        val result = runBlocking { source(host = "").popular(1) }
        assertTrue("expected a Failure, got $result", result is FictionResult.Failure)
        assertTrue(
            "the message should tell the user to configure a host: $result",
            (result as FictionResult.Failure).message.contains("host", ignoreCase = true),
        )
    }

    @Test fun `a non-LAN host is refused before any request goes out`() {
        val result = runBlocking { source(host = "http://example.com").popular(1) }
        assertTrue("expected a Failure, got $result", result is FictionResult.Failure)
        assertEquals(
            "a rejected host must not have issued a request",
            0,
            server.requestCount,
        )
    }

    // ─── inert surfaces ────────────────────────────────────────────────

    @Test fun `no follow or genre surface is faked`() = runBlocking {
        val src = source()
        assertTrue(src.supportsFollow.not())
        assertTrue((src.genres() as FictionResult.Success).value.isEmpty())
        assertTrue((src.followsList(1) as FictionResult.Success).value.items.isEmpty())
        assertTrue((src.byGenre("fantasy", 1) as FictionResult.Success).value.items.isEmpty())
        Unit
    }
}
