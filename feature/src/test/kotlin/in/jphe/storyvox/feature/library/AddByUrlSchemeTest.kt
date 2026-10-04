package `in`.jphe.storyvox.feature.library

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #584 — pin the scheme allowlist for the Add-by-URL flow.
 *
 * The stress test captured three regressions that all collapse to
 * "the previous flow accepted anything":
 *   1. `file:///etc/passwd` — silently queued.
 *   2. `javascript:alert(1)` — silently queued.
 *   3. `not_a_url` — silently queued.
 *
 * The helper [isLikelyAddByUrl] is the single guard that prevents
 * those from reaching the resolver. Exercised directly rather than
 * through the VM coroutine harness — same pattern as
 * [in.jphe.storyvox.feature.sync.isLikelyEmail].
 */
class AddByUrlSchemeTest {

    @Test
    fun `http and https URLs pass`() {
        assertTrue(isLikelyAddByUrl("http://example.com/a"))
        assertTrue(isLikelyAddByUrl("https://example.com/a"))
        assertTrue(isLikelyAddByUrl("https://archiveofourown.org/works/12345"))
        assertTrue(isLikelyAddByUrl("https://www.royalroad.com/fiction/12345"))
    }

    @Test
    fun `uppercase scheme also passes`() {
        // Some browser/clipboard paths emit `HTTP://`; RFC 3986 §3.1
        // says schemes are case-insensitive.
        assertTrue(isLikelyAddByUrl("HTTPS://example.com/x"))
        assertTrue(isLikelyAddByUrl("Http://example.com/y"))
    }

    @Test
    fun `bare scheme without authority rejected`() {
        // `http://` alone — no host. We require AT LEAST one char
        // after the scheme separator so the resolver can do something.
        assertFalse(isLikelyAddByUrl("http://"))
        assertFalse(isLikelyAddByUrl("https://"))
    }

    @Test
    fun `file scheme rejected`() {
        // The stress test's exact repro. file:// scheme reaches the
        // filesystem on Android — never something the user actually
        // wants to add as a fiction. Reject hard.
        assertFalse(isLikelyAddByUrl("file:///etc/passwd"))
        assertFalse(isLikelyAddByUrl("file://localhost/data/user/0/in.jphe.storyvox/files/foo"))
    }

    @Test
    fun `javascript scheme rejected`() {
        // The classic XSS surface; even in a strictly-non-rendering
        // context like Storyvox's resolver, we want zero ambiguity.
        assertFalse(isLikelyAddByUrl("javascript:alert(1)"))
        assertFalse(isLikelyAddByUrl("javascript:void(0)"))
    }

    @Test
    fun `Android-specific schemes rejected`() {
        // `content://` reaches arbitrary ContentProviders.
        assertFalse(isLikelyAddByUrl("content://com.android.providers.media/external"))
        // `intent://` reaches the Activity-resolution surface.
        assertFalse(isLikelyAddByUrl("intent://example.com#Intent;scheme=https;end"))
        // `data:` is a payload-in-URL — never a fiction.
        assertFalse(isLikelyAddByUrl("data:text/plain,Hello"))
    }

    @Test
    fun `bare strings without scheme rejected`() {
        // The third stress-test repro. We don't auto-prepend `http://`
        // because that hides intent; the user typed `not_a_url` and
        // probably meant something we can't help with.
        assertFalse(isLikelyAddByUrl("not_a_url"))
        assertFalse(isLikelyAddByUrl("example.com"))
        assertFalse(isLikelyAddByUrl("example.com/path"))
    }

    @Test
    fun `whitespace is trimmed before scheme check`() {
        // The stress test specifically noted a trailing space made
        // `'file:///etc/passwd '` slip past the previous "supported"
        // hint. The trim happens here AND in the VM call site — both
        // layers guard the same invariant.
        assertFalse(isLikelyAddByUrl("  file:///etc/passwd  "))
        assertTrue(isLikelyAddByUrl("  https://example.com/x  "))
    }

    @Test
    fun `empty string rejected`() {
        assertFalse(isLikelyAddByUrl(""))
        assertFalse(isLikelyAddByUrl("   "))
    }

    @Test
    fun `scheme-prefix-only attacks rejected`() {
        // Some injection patterns try to look like http but aren't —
        // e.g. `httpx://`, `https-something://`. The startsWith check
        // requires the EXACT scheme + `://` prefix.
        assertFalse(isLikelyAddByUrl("httpx://example.com"))
        assertFalse(isLikelyAddByUrl("https-evil://example.com"))
        assertFalse(isLikelyAddByUrl("http:example.com")) // missing //
        assertFalse(isLikelyAddByUrl("https:example.com")) // missing //
    }
}
