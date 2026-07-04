package `in`.jphe.storyvox.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1507 — the OAuth redirect parser is a pure string function (no
 * android.net.Uri, so it runs as a plain JUnit test, mirroring
 * [ReaderPreloadResolverTest]). MainActivity keys on its result to decide
 * "this WAS our redirect, exchange it and stop" vs "fall through to the
 * normal resolve() path".
 */
class NotionOAuthCallbackParseTest {

    private fun parse(s: String?) = DeepLinkResolver.parseNotionOAuthCallback(s)

    @Test
    fun successRedirect_extractsCodeAndState() {
        val result = parse("candela://oauth/notion?code=abc123&state=nonce-xyz")
        assertEquals(
            DeepLinkResolver.NotionOAuthCallback(code = "abc123", state = "nonce-xyz", error = null),
            result,
        )
    }

    @Test
    fun cancelRedirect_carriesError() {
        val result = parse("candela://oauth/notion?error=access_denied")
        assertEquals(
            DeepLinkResolver.NotionOAuthCallback(code = null, state = null, error = "access_denied"),
            result,
        )
    }

    @Test
    fun urlEncodedValues_areDecoded() {
        // A real Notion code can contain reserved characters; the state
        // nonce is a UUID (safe) but decode both defensively.
        val result = parse("candela://oauth/notion?code=a%2Fb%2Bc&state=n%20once")
        assertEquals("a/b+c", result?.code)
        assertEquals("n once", result?.state)
    }

    @Test
    fun nonMatchingUri_isNull() {
        // Not our redirect → null → MainActivity falls through to resolve().
        assertNull(parse("candela://fiction/royalroad%3A123"))
        assertNull(parse("https://www.royalroad.com/fiction/456"))
        assertNull(parse(null))
    }

    @Test
    fun ourRedirectWithNoQuery_isRecognizedButEmpty() {
        // Still "ours" (non-null) so MainActivity stops here rather than
        // routing it as a stray VIEW intent; all fields null.
        assertEquals(
            DeepLinkResolver.NotionOAuthCallback(code = null, state = null, error = null),
            parse("candela://oauth/notion"),
        )
    }
}
