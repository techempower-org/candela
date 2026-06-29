package `in`.jphe.storyvox.source.googlenews.article

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1295 — failure-path coverage for the pure parse/encode steps of
 * [GoogleNewsUrlDecoder], complementing the happy-path [GoogleNewsUrlDecoderTest].
 *
 * Grounded strictly in the decoder's contract: the `CBMi…` id is an opaque
 * token (NOT base64 — see the class kdoc), so there is no decode-and-throw
 * path. Instead the three pure functions each have a well-defined "give up"
 * behaviour:
 *  - `extractArticleId` → null when no `/articles/<id>` segment (or id is blank).
 *  - `parseSignatureTimestamp` → null unless BOTH `data-n-a-sg` and
 *    `data-n-a-ts` are present.
 *  - `parseBatchResponse` → null when no non-Google http(s) URL is found;
 *    malformed/garbage JSON is swallowed by an internal `runCatching` and the
 *    regex fallback runs, so it never throws.
 * These tests assert exactly those guarantees.
 */
class GoogleNewsUrlDecoderEdgeCasesTest {

    // ── extractArticleId: failure paths ────────────────────────────

    @Test
    fun `empty string yields null id`() {
        // No `/articles/` segment at all → Regex.find returns null.
        assertNull(GoogleNewsUrlDecoder.extractArticleId(""))
    }

    @Test
    fun `blank input yields null id`() {
        // Whitespace-only: still no `/articles/` segment to match.
        assertNull(GoogleNewsUrlDecoder.extractArticleId("   \t\n  "))
    }

    @Test
    fun `garbage non-url input yields null id`() {
        // Arbitrary junk contains no `/articles/<id>` → null.
        assertNull(GoogleNewsUrlDecoder.extractArticleId("!!! not a url @#\$%^&*()"))
    }

    @Test
    fun `wrong-host plain article url still extracts the articles segment`() {
        // The id regex is host-agnostic: it keys off the `/articles/<id>` path
        // segment, not the google-news host. A non-google host that happens to
        // contain that segment still yields the captured id — the decoder does
        // not validate the host here (host/path filtering for the *result* URL
        // happens later in firstPublisherUrl, not at id extraction).
        assertEquals(
            "abc123",
            GoogleNewsUrlDecoder.extractArticleId("https://example.com/articles/abc123"),
        )
    }

    @Test
    fun `already-plain publisher url with no articles segment yields null id`() {
        // A real publisher URL (the resolver's eventual output) has no
        // `/articles/` segment, so it cannot be mistaken for a google-news id.
        assertNull(
            GoogleNewsUrlDecoder.extractArticleId("https://www.example.com/news/story-42"),
        )
    }

    @Test
    fun `articles segment with empty id yields null`() {
        // `/articles/` immediately followed by `?` makes the captured group
        // empty; the `takeIf { it.isNotBlank() }` guard converts that to null.
        assertNull(GoogleNewsUrlDecoder.extractArticleId("https://news.google.com/rss/articles/?oc=5"))
    }

    @Test
    fun `articles segment at path end with trailing slash yields null`() {
        // `/articles/` with nothing before the next `/` → empty capture → null.
        assertNull(GoogleNewsUrlDecoder.extractArticleId("https://news.google.com/rss/articles/"))
    }

    // ── parseSignatureTimestamp: failure paths ─────────────────────

    @Test
    fun `empty html yields null signature timestamp`() {
        // Neither marker present → both regex finds are null → null pair.
        assertNull(GoogleNewsUrlDecoder.parseSignatureTimestamp(""))
    }

    @Test
    fun `blank html yields null signature timestamp`() {
        assertNull(GoogleNewsUrlDecoder.parseSignatureTimestamp("    \n\t "))
    }

    @Test
    fun `html without either marker yields null`() {
        // Plausible page markup but missing the data-n-a-* attributes entirely.
        val html = """<c-wiz><div jsdata="x" data-n-a-id="z"></div></c-wiz>"""
        assertNull(GoogleNewsUrlDecoder.parseSignatureTimestamp(html))
    }

    @Test
    fun `signature present but timestamp missing yields null`() {
        // Contract: BOTH must be present. Only-signature → null.
        assertNull(
            GoogleNewsUrlDecoder.parseSignatureTimestamp("""<div data-n-a-sg="AB_cd-EF12"></div>"""),
        )
    }

    @Test
    fun `timestamp present but signature missing yields null`() {
        // Only-timestamp → null.
        assertNull(
            GoogleNewsUrlDecoder.parseSignatureTimestamp("""<div data-n-a-ts="1719876543"></div>"""),
        )
    }

    // ── parseBatchResponse: failure paths ──────────────────────────

    @Test
    fun `empty response yields null`() {
        // No `[` and no URL anywhere → both structured and fallback paths null.
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(""))
    }

    @Test
    fun `blank response yields null`() {
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse("   \n\n  "))
    }

    @Test
    fun `anti-XSSI prefix only yields null`() {
        // The bare `)]}'` guard with no payload: no `[`, no URL → null.
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(")]}'"))
    }

    @Test
    fun `garbage non-json non-url text yields null`() {
        // Random text with no http(s) URL: regex fallback finds nothing → null.
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse("!!! totally bogus &*( payload )*&"))
    }

    @Test
    fun `corrupt truncated json is swallowed and yields null when no url present`() {
        // An unterminated array starting with `[` would throw inside
        // Json.parseToJsonElement, but it's wrapped in runCatching; with no
        // publisher URL the regex fallback also finds nothing → null (no throw).
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(")]}'\n\n[[[\"wrb.fr\",\"Fbv4je\","))
    }

    @Test
    fun `corrupt truncated json still recovers url via regex fallback`() {
        // Even when the JSON is unparseable (truncated mid-array), the lenient
        // regex fallback scans the raw body and returns the first non-google
        // http(s) URL it finds. Proves the catch path stays functional.
        val resp = ")]}'\n\n[[[\"wrb.fr\",\"Fbv4je\",\"https://publisher.example.org/x" // no closing bracket/quote
        assertEquals(
            "https://publisher.example.org/x",
            GoogleNewsUrlDecoder.parseBatchResponse(resp),
        )
    }

    @Test
    fun `response with only google urls yields null`() {
        // Every URL contains google.com → firstPublisherUrl filters them all out.
        val resp = ")]}'\n\n" +
            """[["wrb.fr","Fbv4je","[\"garturlres\",\"https://news.google.com/rss/articles/CBMi\"]"]]"""
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(resp))
    }

    @Test
    fun `response with only gstatic asset urls yields null`() {
        // gstatic.com is also filtered out (asset host, not a publisher).
        val resp = ")]}'\n\n" +
            """[["wrb.fr","Fbv4je","[\"x\",\"https://www.gstatic.com/og/logo.png\"]"]]"""
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse(resp))
    }

    @Test
    fun `already-plain non-redirect text with no http url yields null`() {
        // A protocol-relative / schemeless reference is not matched by the
        // `https?://` regex, so there is no publisher URL to return → null.
        assertNull(GoogleNewsUrlDecoder.parseBatchResponse("ftp://files.example.com/a  //cdn.example.com/b"))
    }
}
