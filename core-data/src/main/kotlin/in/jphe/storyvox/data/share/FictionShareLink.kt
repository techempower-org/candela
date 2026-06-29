package `in`.jphe.storyvox.data.share

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Issue #1313 — shareable deep link for a fiction.
 *
 * Format: `candela://fiction/<url-encoded fictionId>`
 *
 * The fictionId already carries its source — `gutenberg:84`, `epub:<hash>`,
 * `ao3:123`, … — or is a bare numeric id (Royal Road). `FictionRepository`
 * routes it to the right backend by that prefix (`sourceIdForFictionId`,
 * #1298), so the whole id in a single path segment is all the link needs;
 * no separate `sourceId` segment.
 *
 * The id is percent-encoded so reserved characters survive one path segment
 * — the `:` in every prefixed id, and the `/` in ids like
 * `discord:guild/channel`. [build] and [parse] are exact inverses.
 *
 * Pure string operations (no `android.net.Uri`) so the build side
 * (FictionDetail share) and the parse side (`DeepLinkResolver`) use one
 * implementation and it unit-tests without Robolectric. `DeepLinkResolver`
 * feeds [parse] the inbound intent's `dataString`.
 */
object FictionShareLink {
    const val SCHEME: String = "candela"
    const val HOST: String = "fiction"

    /** `candela://fiction/` — the scheme + authority every link starts with. */
    private const val PREFIX: String = "$SCHEME://$HOST/"

    /** Build the shareable link for [fictionId]. */
    fun build(fictionId: String): String = PREFIX + encode(fictionId)

    /**
     * If [uri] is a fiction share link, return its decoded fictionId; else
     * null. Tolerant of case in the scheme/authority (RFC 3986 §3.1 — and
     * Android lowercases the scheme) and of a query/fragment or trailing
     * slash tail.
     */
    fun parse(uri: String): String? {
        val trimmed = uri.trim()
        if (!trimmed.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return null
        val tail = trimmed.substring(PREFIX.length)
            .substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
        if (tail.isEmpty()) return null
        return runCatching { decode(tail) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun encode(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())
    private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
}
