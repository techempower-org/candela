package `in`.jphe.storyvox.source.rss.parse

import java.net.URI

/**
 * Issue #1489 — RSS / Atom autodiscovery.
 *
 * Users paste a site's homepage (e.g. `tricycle.org`), not its feed URL.
 * Without autodiscovery the app fetches the HTML forever, [RssParser] throws,
 * and the user is left staring at an empty chapter list. Standard pages carry
 * the feed as a `<head>` hint:
 *
 * ```html
 * <link rel="alternate" type="application/rss+xml" href="https://tricycle.org/feed/">
 * ```
 *
 * [discoverFeedUrl] scans an HTML body for that hint and returns an ABSOLUTE
 * feed URL, or null if the page advertises none.
 *
 * Deliberately a tolerant string/regex scan, NOT an XML parse: (a) it must run
 * on messy real-world HTML that no XML parser would accept, and (b) it keeps
 * this unit plain-JUnit testable — `android.util.Xml` (what [RssParser] uses)
 * isn't on the JVM test classpath and the project bars Robolectric.
 *
 * Selection order: skip comment feeds (`/comments/`) when a non-comment feed
 * exists; prefer `application/rss+xml` over `application/atom+xml`; first match
 * wins within a type.
 */
internal fun discoverFeedUrl(html: String, pageUrl: String): String? {
    val linkTags = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE)
    val candidates = mutableListOf<Pair<String, String>>() // (type, absoluteHref)

    for (match in linkTags.findAll(html)) {
        val tag = match.value
        val rel = attr(tag, "rel")?.lowercase() ?: continue
        // rel may be a token list ("alternate home"); require the alternate token.
        if (!rel.split(Regex("\\s+")).contains("alternate")) continue
        val type = attr(tag, "type")?.lowercase() ?: continue
        if (!type.contains("application/rss+xml") && !type.contains("application/atom+xml")) continue
        val href = attr(tag, "href")?.trim().takeUnless { it.isNullOrEmpty() } ?: continue
        val absolute = resolveUrl(pageUrl, href) ?: continue
        candidates += type to absolute
    }
    if (candidates.isEmpty()) return null

    // Prefer a non-comment feed if one exists (a WordPress page advertises both
    // its main feed and a /comments/feed/).
    val pool = candidates.filterNot { it.second.contains("/comments/", ignoreCase = true) }
        .ifEmpty { candidates }
    // Prefer RSS over Atom when a page offers both.
    return (pool.firstOrNull { it.first.contains("application/rss+xml") } ?: pool.first()).second
}

/** Extract a single/double-quoted attribute value from one tag, or null. */
private fun attr(tag: String, name: String): String? =
    Regex("""\b$name\s*=\s*("([^"]*)"|'([^']*)')""", RegexOption.IGNORE_CASE)
        .find(tag)
        ?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

/** Resolve [href] (absolute or relative) against the fetched [base]. Null if
 *  the base isn't a usable absolute URL and the href isn't already absolute. */
private fun resolveUrl(base: String, href: String): String? = runCatching {
    URI(base).resolve(href).toString()
}.getOrNull()
