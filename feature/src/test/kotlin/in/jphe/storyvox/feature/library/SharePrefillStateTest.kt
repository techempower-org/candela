package `in`.jphe.storyvox.feature.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1679 — Chrome "Share → Candela" (and the clipboard magic-link) opened
 * the app but never added the URL: the `sharedUrl` nav arg was plumbed all the
 * way to `LibraryScreen` and then dropped (no consumer). [sharePrefillState] is
 * the pure decision that now bridges the arg into the Add-by-URL sheet.
 *
 * Exercised directly (no VM coroutine harness) — same pattern as
 * [AddByUrlSchemeTest] over [isLikelyAddByUrl], which this composes on top of.
 */
class SharePrefillStateTest {

    @Test
    fun `null or blank yields no sheet`() {
        assertNull(sharePrefillState(null))
        assertNull(sharePrefillState(""))
        assertNull(sharePrefillState("   "))
        assertNull(sharePrefillState("\n\t "))
    }

    @Test
    fun `non-http(s) or scheme-only text yields no sheet`() {
        assertNull(sharePrefillState("just some shared text"))
        assertNull(sharePrefillState("ftp://example.com/file"))
        assertNull(sharePrefillState("file:///etc/passwd"))
        assertNull(sharePrefillState("http://")) // scheme only, no authority
        assertNull(sharePrefillState("https://")) // scheme only, no authority
    }

    @Test
    fun `http and https URLs open the sheet pre-filled, trimmed`() {
        assertEquals(
            AddByUrlSheetState.Open(prefill = "https://example.com/story/1"),
            sharePrefillState("  https://example.com/story/1  "),
        )
        assertEquals(
            AddByUrlSheetState.Open(prefill = "http://www.royalroad.com/fiction/12345"),
            sharePrefillState("http://www.royalroad.com/fiction/12345"),
        )
    }

    @Test
    fun `scheme is case-insensitive but the prefill preserves original casing`() {
        assertEquals(
            AddByUrlSheetState.Open(prefill = "HTTPS://Example.com/X"),
            sharePrefillState("HTTPS://Example.com/X"),
        )
    }

    @Test
    fun `prefill never carries an error (it is a fresh open, not a retry)`() {
        val open = sharePrefillState("https://example.com/a")
        assertEquals(null, open?.error)
    }
}
