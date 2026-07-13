package `in`.jphe.storyvox.source.hackernews

import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #379 — JSON-shape tests for the HN catalog and Algolia
 * search parsers. These run in pure JVM (no Android dependencies)
 * because the parse logic is split out into `internal` functions on
 * [HackerNewsApi] and free top-level helpers in [HackerNewsSource]'s
 * package; the OkHttp transport is mocked away by calling the parse
 * helpers directly.
 *
 * Three test classes cover the three required surfaces:
 *  - `parseTopStoryArray` — list-of-ints parsing on
 *    `/v0/topstories.json` (verifies the `JsonArray` fast path used
 *    by all three list endpoints).
 *  - `parseItemAndRenderLinkStory` — single-item parsing on
 *    `/v0/item/<id>.json`, then the HTML-strip/composition that
 *    feeds the chapter body for a link-type story.
 *  - `parseAlgoliaSearchResponse` — Algolia search response mapping
 *    to FictionSummary-shaped data.
 */
class HackerNewsParseTest {

    private val api = HackerNewsApi(OkHttpClient())

    @Test
    fun `parses top stories id array`() {
        // Trimmed fixture from a real /v0/topstories.json response.
        // The real endpoint returns ~500 ids; the parser is dumb to
        // length so 6 is enough to exercise the JsonArray path.
        val fixture = "[39000000, 39000001, 39000002, 39000003, 39000004, 39000005]"
        val ids = api.parseIdArray(fixture)
        assertEquals(6, ids.size)
        assertEquals(39000000L, ids[0])
        assertEquals(39000005L, ids[5])
    }

    @Test
    fun `parses item null body as null`() {
        // HN's API serves the literal four bytes "null" with HTTP
        // 200 for missing / deleted items. The parser must treat
        // that as null, not as a parse error.
        val parsed = api.parseItem("null")
        assertNull(parsed)
    }

    @Test
    fun `parses Ask HN story with text body`() {
        val fixture = """
            {
              "id": 8863,
              "type": "story",
              "by": "dhouston",
              "time": 1175714200,
              "title": "Ask HN: Anyone use a tool for X?",
              "text": "I'm looking for &lt;widgets&gt; that handle Y. Any pointers?",
              "score": 111,
              "descendants": 7,
              "kids": [9224, 9225]
            }
        """.trimIndent()
        val item = api.parseItem(fixture)
        assertNotNull(item)
        assertEquals(8863L, item!!.id)
        assertEquals("story", item.type)
        assertEquals("dhouston", item.by)
        assertEquals("Ask HN: Anyone use a tool for X?", item.title)
        assertTrue(item.text!!.contains("&lt;widgets&gt;"))
        assertEquals(listOf(9224L, 9225L), item.kids)
    }

    @Test
    fun `html-strip decodes entities and collapses whitespace`() {
        // The to-plain-text helper is what turns HN's HTML-fragmented
        // text/comment bodies into the engine-friendly plaintext the
        // chapter content carries downstream.
        val raw = "I&#x27;m looking for <i>widgets</i> that &amp; do  X."
        val plain = raw.toPlainText()
        assertEquals("I'm looking for widgets that & do X.", plain)
    }

    @Test
    fun `toPlainText decodes the wider entity table and merges adjacent tags (#1628)`() {
        // &mdash; is outside the old 7-entity table — the entity-gap FIX.
        assertEquals("Wit — wonder", "Wit &mdash; wonder".toPlainText())
        // R1: the old stripper put a SPACE where a tag sat; the shared util
        // uses source whitespace, so an intra-word tag merges (chosen,
        // pinned outcome — prose always spaces its emphasis anyway).
        assertEquals("Skynetrises", "Sky<i>net</i>rises".toPlainText())
    }

    @Test
    fun `parses Algolia search response into hits`() {
        // Truncated real Algolia response. The real shape has lots
        // more fields (highlight ranges, _tags, created_at, etc.) —
        // ignoreUnknownKeys = true in the API client lets these pass
        // through without breaking the decode.
        val fixture = """
            {
              "hits": [
                {
                  "objectID": "39001234",
                  "title": "Show HN: A new database engine",
                  "author": "alice",
                  "url": "https://example.com/db-engine",
                  "points": 423,
                  "num_comments": 89,
                  "_tags": ["story", "author_alice", "story_39001234"],
                  "created_at": "2026-01-15T08:30:00Z"
                },
                {
                  "objectID": "39001235",
                  "title": "Ask HN: How do you debug X?",
                  "author": "bob",
                  "story_text": "I'm trying to figure out the right way to debug...",
                  "points": 87,
                  "num_comments": 12,
                  "_tags": ["story", "ask_hn", "author_bob"]
                }
              ],
              "nbPages": 5,
              "page": 0,
              "hitsPerPage": 20
            }
        """.trimIndent()

        // Decode through the same Json instance the api uses by
        // routing the fixture through the public API would require
        // mocking OkHttp; instead we decode through the Algolia
        // wire type directly here.
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true; isLenient = true
        }
        val parsed = json.decodeFromString<`in`.jphe.storyvox.source.hackernews.net.AlgoliaSearchResponse>(fixture)
        assertEquals(2, parsed.hits.size)
        assertEquals(5, parsed.nbPages)
        assertEquals("39001234", parsed.hits[0].objectId)
        assertEquals("Show HN: A new database engine", parsed.hits[0].title)
        assertEquals("alice", parsed.hits[0].author)
        assertEquals(423, parsed.hits[0].points)
        assertEquals("39001235", parsed.hits[1].objectId)
        assertEquals(87, parsed.hits[1].points)
    }

    @Test
    fun `fiction id encoding round-trips`() {
        val parsed = parseHackerNewsId("hackernews:39001234")
        assertEquals(39001234L, parsed)
        assertNull(parseHackerNewsId("notvalid"))
        assertNull(parseHackerNewsId("hackernews:not-a-number"))
    }
}
