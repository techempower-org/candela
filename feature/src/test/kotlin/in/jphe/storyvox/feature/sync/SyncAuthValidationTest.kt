package `in`.jphe.storyvox.feature.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #583 — pin the spec for the two pure helpers that prevent the
 * raw InstantDB parser error (`Malformed parameter: [\`) from leaking
 * to the user. We exercise the helpers directly rather than booting
 * the VM coroutine harness, matching the
 * [SyncOnboardingViewModelTest.shouldShowOnboarding] pattern.
 *
 * The split is intentional: [isLikelyEmail] is the gate that should
 * have caught the 200-char garbage input before it ever hit the
 * network; [sanitizeAuthError] is the belt-and-suspenders for any
 * server error that does come back with parser-token noise.
 */
class SyncAuthValidationTest {

    // ===== isLikelyEmail =====

    @Test
    fun `obvious good email passes`() {
        assertTrue(isLikelyEmail("user@example.com"))
        assertTrue(isLikelyEmail("a@b.co"))
        assertTrue(isLikelyEmail("user+tag@sub.example.museum"))
        assertTrue(isLikelyEmail("first.last@example.org"))
    }

    @Test
    fun `whitespace is trimmed before validation`() {
        // The VM already calls .trim() before passing to the helper,
        // but the helper trims too so the spec is self-contained.
        assertTrue(isLikelyEmail("  user@example.com  "))
    }

    @Test
    fun `empty input rejected`() {
        assertFalse(isLikelyEmail(""))
        assertFalse(isLikelyEmail("   "))
    }

    @Test
    fun `missing at-sign rejected`() {
        // The original gate (`!contains('@')`) was correct on this case;
        // pin it so a future refactor doesn't lose it.
        assertFalse(isLikelyEmail("user.example.com"))
        assertFalse(isLikelyEmail("aaaaaaa"))
    }

    @Test
    fun `multiple at-signs rejected`() {
        assertFalse(isLikelyEmail("a@b@c.com"))
    }

    @Test
    fun `missing local part rejected`() {
        assertFalse(isLikelyEmail("@example.com"))
    }

    @Test
    fun `missing domain rejected`() {
        assertFalse(isLikelyEmail("user@"))
    }

    @Test
    fun `domain without dot rejected`() {
        // A bare hostname is invalid for a sign-in flow that emails
        // a code. We don't accept `user@localhost` even though it's
        // technically valid in some contexts.
        assertFalse(isLikelyEmail("user@localhost"))
    }

    @Test
    fun `domain with leading or trailing dot rejected`() {
        assertFalse(isLikelyEmail("user@.example.com"))
        assertFalse(isLikelyEmail("user@example."))
    }

    @Test
    fun `whitespace inside local or domain rejected`() {
        // The stress test's 200-char `aaaa...@b.c` happened to have no
        // internal whitespace, but space-stuffed input is the classic
        // copy-paste case (autocorrect inserting a space after a tap).
        assertFalse(isLikelyEmail("user name@example.com"))
        assertFalse(isLikelyEmail("user@exa mple.com"))
    }

    @Test
    fun `comma or semicolon rejected`() {
        // These are the InstantDB JSON-parser trigger tokens — never
        // valid in an email, and rejecting them client-side stops the
        // raw `Malformed parameter: [\` leak before it can happen.
        assertFalse(isLikelyEmail("user,test@example.com"))
        assertFalse(isLikelyEmail("user;test@example.com"))
    }

    @Test
    fun `over-length input rejected`() {
        // The stress test's exact repro: 180 a's + @b.c = ~184 chars.
        // Under the total 254-char cap, but its 180-char local part
        // exceeds RFC 5321 §4.5.3.1.1's 64-octet limit — this is the
        // boundary the validator must reject.
        val stressRepro = "a".repeat(180) + "@b.c"
        assertFalse(
            "Stress-test repro (180-char local part) must be rejected",
            isLikelyEmail(stressRepro),
        )
        // Local part at the 64-char limit is the upper bound of valid.
        val localAtLimit = "a".repeat(64) + "@example.com"
        assertTrue(
            "64-char local part is the RFC 5321 boundary — must accept",
            isLikelyEmail(localAtLimit),
        )
        // One char over the local limit rejects.
        val localOverLimit = "a".repeat(65) + "@example.com"
        assertFalse(isLikelyEmail(localOverLimit))
        // Total-length cap also rejects (rare but documented).
        val totalOverLimit = "a".repeat(60) + "@" + "b".repeat(200) + ".com"
        assertTrue(
            "Sanity: test setup actually exceeds 254-char cap (got " +
                "${totalOverLimit.length})",
            totalOverLimit.length > 254,
        )
        assertFalse(isLikelyEmail(totalOverLimit))
    }

    // ===== sanitizeAuthError =====

    @Test
    fun `null or blank produces friendly fallback`() {
        val a = sanitizeAuthError(null)
        val b = sanitizeAuthError("")
        val c = sanitizeAuthError("   ")
        assertTrue(a.isNotEmpty())
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `ambiguous malformed-parameter leak is sanitized without blaming the email`() {
        // #1452 — a bare truncated leak (parseError couldn't extract the
        // hint.in field) is ambiguous: it could be app-id or email. We must
        // sanitize the JSON noise AND must NOT claim the email is wrong.
        val sanitized = sanitizeAuthError("Malformed parameter: [\\")
        assertFalse(sanitized.contains('['))
        assertFalse(sanitized.contains('\\'))
        assertFalse(
            "ambiguous param error must not blame the email, got: $sanitized",
            sanitized.lowercase().contains("look right"),
        )
    }

    @Test
    fun `malformed app-id is a config error, not an email error`() {
        // #1452 root cause — a bad shipped INSTANTDB_APP_ID makes InstantDB
        // reject the app-id; the user's email is fine.
        val sanitized = sanitizeAuthError("param-malformed: app-id")
        assertFalse(
            "app-id error must not blame the email, got: $sanitized",
            sanitized.lowercase().contains("look right"),
        )
        assertTrue(
            "expected an update/config hint, got: $sanitized",
            sanitized.lowercase().contains("update") || sanitized.lowercase().contains("set up"),
        )
    }

    @Test
    fun `missing app-id is also a config error, not an email error`() {
        val sanitized = sanitizeAuthError("param-missing: app-id")
        assertFalse(sanitized.lowercase().contains("look right"))
    }

    @Test
    fun `malformed email parameter still maps to the email message`() {
        val sanitized = sanitizeAuthError("param-malformed: email")
        assertTrue(
            "expected email guidance, got: $sanitized",
            sanitized.lowercase().contains("look right"),
        )
    }

    @Test
    fun `explicit invalid-email server message maps to the email message`() {
        assertTrue(sanitizeAuthError("Invalid email address").lowercase().contains("look right"))
    }

    @Test
    fun `trailing JSON-path tokens are trimmed`() {
        // Variants of the parser leak we've seen in the wild.
        assertFalse(sanitizeAuthError("Malformed parameter: [\\\"").contains('"'))
        assertFalse(sanitizeAuthError("Bad input: ]").contains(']'))
        assertFalse(sanitizeAuthError("Error,  ").contains(','))
    }

    @Test
    fun `rate limit message is remapped`() {
        val sanitized = sanitizeAuthError("Rate limit exceeded for IP")
        assertTrue(sanitized.lowercase().contains("wait"))
    }

    @Test
    fun `network errors are remapped`() {
        val sanitized = sanitizeAuthError("Unable to resolve host \"api.instantdb.com\"")
        assertTrue(sanitized.lowercase().contains("sync server"))
    }

    @Test
    fun `paragraph-length messages are capped`() {
        val long = "x".repeat(500)
        val sanitized = sanitizeAuthError(long)
        assertTrue("expected truncation, got len=${sanitized.length}", sanitized.length <= 140)
        assertTrue(sanitized.endsWith("…"))
    }

    @Test
    fun `clean short message passes through unchanged`() {
        // A well-behaved server message ("Email not registered.") should
        // reach the user as-is. We're not trying to nanny every error;
        // only the JSON-noisy ones and the over-long ones.
        val clean = "Email not registered."
        val sanitized = sanitizeAuthError(clean)
        assertEquals(clean, sanitized)
    }

    @Test
    fun `purely punctuation input falls back to friendly default`() {
        // Edge case: server returns `[\]` (all noise). After trimming
        // there's nothing left → we should give the user the friendly
        // fallback, not a blank string.
        val sanitized = sanitizeAuthError("[\\]")
        assertTrue(sanitized.isNotBlank())
        assertNotEquals("[\\]", sanitized)
    }
}
