package `in`.jphe.storyvox.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1496 — the Google Drive OAuth redirect parser is a pure string
 * function (no android.net.Uri), so it runs as a plain JUnit test, mirroring
 * [NotionOAuthCallbackParseTest]. MainActivity keys on its result to decide
 * "this WAS our redirect, exchange it and stop" vs "fall through to resolve()".
 */
class GoogleDriveOAuthCallbackParseTest {

    private fun parse(s: String?) = DeepLinkResolver.parseGoogleDriveOAuthCallback(s)

    @Test
    fun successRedirect_extractsCodeAndState() {
        val result = parse("candela://oauth/googledrive?code=4/abc123&state=nonce-xyz")
        assertEquals(
            DeepLinkResolver.GoogleDriveOAuthCallback(code = "4/abc123", state = "nonce-xyz", error = null),
            result,
        )
    }

    @Test
    fun cancelRedirect_carriesError() {
        val result = parse("candela://oauth/googledrive?error=access_denied")
        assertEquals(
            DeepLinkResolver.GoogleDriveOAuthCallback(code = null, state = null, error = "access_denied"),
            result,
        )
    }

    @Test
    fun urlEncodedValues_areDecoded() {
        // Google auth codes routinely contain reserved chars (a leading
        // "4/..." with slashes and pluses); decode defensively.
        val result = parse("candela://oauth/googledrive?code=4%2F0Ab%2Bc%2Fd&state=n%20once")
        assertEquals("4/0Ab+c/d", result?.code)
        assertEquals("n once", result?.state)
    }

    @Test
    fun nonMatchingUri_isNull() {
        assertNull(parse("candela://oauth/notion?code=x&state=y"))
        assertNull(parse("candela://fiction/royalroad%3A123"))
        assertNull(parse("https://drive.google.com/whatever"))
        assertNull(parse(null))
    }

    @Test
    fun ourRedirectWithNoQuery_isRecognizedButEmpty() {
        assertEquals(
            DeepLinkResolver.GoogleDriveOAuthCallback(code = null, state = null, error = null),
            parse("candela://oauth/googledrive"),
        )
    }
}
