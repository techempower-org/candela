package `in`.jphe.storyvox.source.palace.parse

import `in`.jphe.storyvox.data.text.htmlToInlineText
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI

/**
 * Issue #502 — OPDS 1.x catalog feed parser using Android's bundled
 * [XmlPullParser]. Same posture as `:source-rss`'s feed parser (#236):
 * hand-rolled, narrow surface, no Rome-style dependency cost.
 *
 * Scope:
 *  - OPDS 1.x (Atom-flavoured XML) — the profile every Palace Project
 *    library exposes today. OPDS 2.x (JSON) is not handled here and is
 *    a follow-up.
 *  - Acquisition feeds: lists of publications. Each `<entry>` becomes an
 *    [OpdsEntry] with its `<link>` list intact so the caller can pick
 *    the right acquisition link (open-access EPUB vs LCP license vs
 *    audiobook manifest).
 *  - Navigation feeds: lists of sub-feed links. The parser extracts
 *    these into [OpdsFeed.navLinks] so the source can emit them as
 *    "genre" / "collection" picker rows.
 *
 * What this parser does **not** do:
 *  - OPDS 2.x JSON profile (deferred — see [parse] comment)
 *  - OpenSearch description documents (the search-endpoint discovery
 *    metadata; storyvox composes the search URL directly from the
 *    library base for v1)
 *  - Per-entry indirect-acquisition chains (used for some DRM-multi-
 *    format flows on Palace; v1 only handles the top-level link
 *    list and routes everything else to the deep-link CTA)
 *
 * Stateless: the singleton holds no per-feed state. Concurrent parses
 * each get their own XmlPullParser instance.
 */
internal object OpdsParser {

    /**
     * Entry point. [baseUrl] is the request URL the XML came from; it
     * resolves any relative `href` attributes the feed carries (Palace
     * libraries frequently emit relative paths on the `next` pagination
     * link and on per-entry covers).
     *
     * Returns [OpdsFeed] on success, throws [OpdsParseException] on a
     * fundamentally malformed feed (no `<feed>` root, no entries with
     * titles). Empty acquisition feeds (`<feed>` with zero `<entry>`)
     * are valid and return [OpdsFeed] with an empty entries list — the
     * UI renders "no titles in this collection" cleanly.
     */
    fun parse(xml: String, baseUrl: String): OpdsFeed {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        parser.nextTag()
        if (parser.name != "feed") {
            throw OpdsParseException(
                "Not an OPDS 1.x Atom feed (root=${parser.name}). " +
                    "OPDS 2.x JSON parsing is a follow-up.",
            )
        }
        return parseFeed(parser, baseUrl)
    }

    private fun parseFeed(parser: XmlPullParser, baseUrl: String): OpdsFeed {
        parser.require(XmlPullParser.START_TAG, null, "feed")
        var feedTitle = ""
        var feedId: String? = null
        var nextHref: String? = null
        val publicationEntries = mutableListOf<OpdsEntry>()
        val navLinks = mutableListOf<OpdsNavLink>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "feed") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "title" -> feedTitle = readText(parser).trim()
                "id" -> feedId = readText(parser).trim().takeIf { it.isNotEmpty() }
                "link" -> {
                    val link = readLink(parser, baseUrl)
                    if (link.rel == OpdsRel.NEXT) nextHref = link.href
                    // Other feed-level links (self, search, start) are
                    // informational only — the source composes its own
                    // URLs from the library base.
                }
                "entry" -> {
                    val classified = parseEntry(parser, baseUrl)
                    when (classified) {
                        is EntryShape.Publication -> publicationEntries += classified.entry
                        is EntryShape.Navigation -> navLinks += classified.nav
                        // Entries that have neither a publication-shaped
                        // acquisition link nor a nav-link target are
                        // dropped — they typically belong to feed-meta
                        // surfaces ("about this library") we don't
                        // surface in v1.
                        EntryShape.Empty -> Unit
                    }
                }
                else -> skip(parser)
            }
        }
        if (feedTitle.isBlank()) {
            // Palace feeds always carry `<title>`. If we land here it's
            // a structurally broken feed; surface that loudly rather
            // than silently rendering an empty "Untitled" lane.
            throw OpdsParseException("OPDS feed missing required <title>")
        }
        return OpdsFeed(
            title = feedTitle,
            id = feedId,
            entries = publicationEntries,
            navLinks = navLinks,
            nextHref = nextHref,
        )
    }

    /**
     * Classify an `<entry>` as a publication, a navigation row, or
     * empty. The OPDS profile uses link `rel` + `type` to disambiguate;
     * we read every link first, then decide on the entry's role.
     */
    private fun parseEntry(parser: XmlPullParser, baseUrl: String): EntryShape {
        parser.require(XmlPullParser.START_TAG, null, "entry")
        var id = ""
        var title = ""
        var author: String? = null
        var summary: String? = null
        val categories = mutableListOf<String>()
        val links = mutableListOf<OpdsLink>()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "entry") break
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "id" -> id = readText(parser).trim()
                "title" -> title = readText(parser).trim()
                "author" -> author = readAuthor(parser) ?: author
                "summary", "content" -> {
                    // First non-empty wins. `<summary>` is preferred (plain
                    // text by spec); `<content>` is HTML and we strip it
                    // crudely so it renders as a plain card subtitle.
                    val txt = readText(parser).trim()
                    if (summary.isNullOrBlank() && txt.isNotEmpty()) {
                        summary = txt.htmlToInlineText()
                    }
                }
                "category" -> {
                    // Atom `<category term="…" label="…"/>` — prefer the
                    // human-readable `label` when present, fall back to
                    // the machine `term`.
                    val label = parser.getAttributeValue(null, "label")
                    val term = parser.getAttributeValue(null, "term")
                    (label ?: term)?.trim()?.takeIf { it.isNotEmpty() }
                        ?.let { categories += it }
                    skip(parser)
                }
                "link" -> links += readLink(parser, baseUrl)
                else -> skip(parser)
            }
        }
        if (id.isBlank() || title.isBlank()) return EntryShape.Empty

        // If any link is acquisition-shaped, the entry is a publication
        // — even if it's a DRM'd one we can't fulfil. The source-side
        // code decides what to do per link `type`.
        val hasAcquisition = links.any { it.rel.startsWith(OpdsRel.ACQUISITION) }
        if (hasAcquisition) {
            val coverUrl = pickCover(links)
            return EntryShape.Publication(
                OpdsEntry(
                    id = id,
                    title = title,
                    author = author,
                    summary = summary,
                    coverUrl = coverUrl,
                    categories = categories,
                    links = links,
                ),
            )
        }

        // Navigation entries link out to a sub-feed via an Atom-typed
        // link (no acquisition rel). Take the first such link as the
        // collection's target.
        val nav = links.firstOrNull { link ->
            val t = link.type ?: return@firstOrNull false
            t.startsWith("application/atom+xml") && t.contains("opds-catalog")
        }
        if (nav != null) {
            return EntryShape.Navigation(
                OpdsNavLink(title = title, href = nav.href, summary = summary),
            )
        }

        return EntryShape.Empty
    }

    private fun readLink(parser: XmlPullParser, baseUrl: String): OpdsLink {
        val rel = parser.getAttributeValue(null, "rel") ?: ""
        val href = parser.getAttributeValue(null, "href") ?: ""
        val type = parser.getAttributeValue(null, "type")
        val title = parser.getAttributeValue(null, "title")
        skip(parser) // <link/> is self-closing in OPDS; this consumes the END_TAG.
        return OpdsLink(
            rel = rel,
            href = resolve(baseUrl, href),
            type = type,
            title = title,
        )
    }

    /**
     * Atom `<author><name>...</name></author>`. Some Palace feeds put
     * just a text node directly inside `<author>` (non-conformant but
     * present in the wild); accept both shapes.
     */
    private fun readAuthor(parser: XmlPullParser): String? {
        parser.require(XmlPullParser.START_TAG, null, "author")
        var name: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "author") break
            if (parser.eventType == XmlPullParser.TEXT && name.isNullOrBlank()) {
                val txt = parser.text?.trim()
                if (!txt.isNullOrEmpty()) name = txt
            } else if (parser.eventType == XmlPullParser.START_TAG && parser.name == "name") {
                name = readText(parser).trim()
            } else if (parser.eventType == XmlPullParser.START_TAG) {
                skip(parser)
            }
        }
        return name?.takeIf { it.isNotEmpty() }
    }

    /** Pick the largest available cover image. Prefer `image` over
     *  `image/thumbnail` (thumbnail is smaller; storyvox's list rows
     *  render at 96-128 dp so the larger image's filesize cost is
     *  worth it for retina sharpness). */
    private fun pickCover(links: List<OpdsLink>): String? {
        return links.firstOrNull { it.rel == OpdsRel.IMAGE }?.href
            ?: links.firstOrNull { it.rel == OpdsRel.IMAGE_THUMBNAIL }?.href
    }

    // ── xml helpers ─────────────────────────────────────────────────

    /** Consume an element's text content and advance to its end tag. */
    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.TEXT -> sb.append(parser.text)
                XmlPullParser.END_TAG -> return sb.toString()
                XmlPullParser.START_TAG -> {
                    // Some Palace feeds nest HTML inside `<content type="html">`.
                    // The XmlPullParser surfaces those as nested START_TAGs.
                    // Recurse to gather the contained text; the caller strips
                    // markup if needed.
                    sb.append(readText(parser))
                }
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** Skip the current element (including any children). */
    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                else -> Unit
            }
        }
    }

    /**
     * Resolve a relative href against the feed's base URL using
     * [java.net.URI]. Palace feeds frequently emit absolute hrefs for
     * cover images and relative paths for pagination links; both shapes
     * need to come out as absolute strings the OkHttp layer can hit.
     */
    private fun resolve(baseUrl: String, href: String): String {
        if (href.isEmpty()) return href
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        return try {
            URI(baseUrl).resolve(href).toString()
        } catch (e: Exception) {
            // If the base URL is itself malformed (shouldn't happen
            // because OkHttp would have refused the request that
            // returned this feed), return the href unchanged. Failing
            // here would crash the whole parse for a single bad link.
            href
        }
    }

    /** Classified entry shape. The parser walks once and dispatches in
     *  the caller; an entry can be exactly one of these. */
    private sealed interface EntryShape {
        data class Publication(val entry: OpdsEntry) : EntryShape
        data class Navigation(val nav: OpdsNavLink) : EntryShape
        data object Empty : EntryShape
    }
}

/**
 * Thrown for structurally invalid feeds (no `<feed>` root, no `<title>`).
 * RuntimeException subclass — surfaces as `FictionResult.NetworkError`
 * at the source-side call site.
 */
internal class OpdsParseException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
