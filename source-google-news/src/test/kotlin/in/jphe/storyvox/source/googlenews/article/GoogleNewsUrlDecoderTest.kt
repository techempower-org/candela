package `in`.jphe.storyvox.source.googlenews.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/**
 * Issue #1295 — covers the pure parse/encode steps of [GoogleNewsUrlDecoder].
 * These are the fragile bits (Google changes the format periodically), so each
 * is pinned against representative payloads. The network shell (`decode`) is
 * the only untested part — verified on-device when the feature flag is enabled.
 */
class GoogleNewsUrlDecoderTest {

    // ── extractArticleId ───────────────────────────────────────────

    @Test
    fun `extracts article id with query string`() {
        val url = "https://news.google.com/rss/articles/CBMiABCDEF123?oc=5&hl=en-US"
        assertEquals("CBMiABCDEF123", GoogleNewsUrlDecoder.extractArticleId(url))
    }

    @Test
    fun `extracts article id without query`() {
        val url = "https://news.google.com/rss/articles/CBMixyz"
        assertEquals("CBMixyz", GoogleNewsUrlDecoder.extractArticleId(url))
    }

    @Test
    fun `non-article url yields null id`() {
        assertNull(GoogleNewsUrlDecoder.extractArticleId("https://news.google.com/topics/CAAq"))
        assertNull(GoogleNewsUrlDecoder.extractArticleId("https://example.com/foo"))
    }

    // ── parseSignatureTimestamp ────────────────────────────────────

    @Test
    fun `parses signature and timestamp off the c-wiz element`() {
        val html = """
            <c-wiz><div jsdata="x" data-n-a-ts="1719876543" data-n-a-sg="AB_cd-EF12" data-n-a-id="z">
            </div></c-wiz>
        """.trimIndent()
        val (sg, ts) = GoogleNewsUrlDecoder.parseSignatureTimestamp(html)!!
        assertEquals("AB_cd-EF12", sg)
        assertEquals("1719876543", ts)
    }

    @Test
    fun `missing signature or timestamp yields null`() {
        assertNull(GoogleNewsUrlDecoder.parseSignatureTimestamp("""<div data-n-a-ts="123"></div>"""))
        assertNull(GoogleNewsUrlDecoder.parseSignatureTimestamp("""<div data-n-a-sg="abc"></div>"""))
        assertNull(GoogleNewsUrlDecoder.parseSignatureTimestamp("<div></div>"))
    }

    // ── buildBatchExecuteBody ──────────────────────────────────────

    @Test
    fun `builds an f_req body embedding id, timestamp, signature, locale`() {
        val body = GoogleNewsUrlDecoder.buildBatchExecuteBody("CBMiID", "1719", "SIG99", "GB:en")
        assertTrue(body.startsWith("f.req="))
        // The value is URL-encoded; decode it back and assert the structure.
        val decoded = URLDecoder.decode(body.removePrefix("f.req="), "UTF-8")
        assertTrue("has the Fbv4je rpc id", decoded.contains("Fbv4je"))
        assertTrue("embeds the article id", decoded.contains("CBMiID"))
        assertTrue("embeds the timestamp (unquoted)", decoded.contains("1719"))
        assertTrue("embeds the signature", decoded.contains("SIG99"))
        assertTrue("embeds the locale", decoded.contains("GB:en"))
        assertTrue("is the nested array shape", decoded.startsWith("[[[\"Fbv4je\""))
    }

    // ── parseBatchResponse ─────────────────────────────────────────

    @Test
    fun `parses the publisher url from a single-array response`() {
        val resp = ")]}'\n\n" +
            """[["wrb.fr","Fbv4je","[\"garturlres\",\"https://www.example.com/news/story-42\"]",null,null,null,"generic"],["di",9],["af.httprm",9,"x",4]]"""
        assertEquals(
            "https://www.example.com/news/story-42",
            GoogleNewsUrlDecoder.parseBatchResponse(resp),
        )
    }

    @Test
    fun `recovers the url from a chunked length-prefixed response via fallback`() {
        // Real responses are sometimes chunked with length prefixes, which is
        // not a single valid JSON document — the regex fallback must still find
        // the publisher URL.
        val resp = ")]}'\n\n" +
            "352\n" +
            """[["wrb.fr","Fbv4je","[\"garturlres\",\"https://publisher.example.org/a/b\"]"]]""" + "\n" +
            "26\n" +
            """[["di",9],["af.httprm",9,"x",4]]"""
        assertEquals(
            "https://publisher.example.org/a/b",
            GoogleNewsUrlDecoder.parseBatchResponse(resp),
        )
    }

    @Test
    fun `ignores google and gstatic urls`() {
        val resp = ")]}'\n\n" +
            """[["wrb.fr","Fbv4je","[\"garturlres\",\"https://news.google.com/rss/articles/CBMi\"]"]]"""
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(resp))
    }

    @Test
    fun `malformed response yields null`() {
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(")]}'"))
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(""))
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse("no urls here at all"))
    }
}
