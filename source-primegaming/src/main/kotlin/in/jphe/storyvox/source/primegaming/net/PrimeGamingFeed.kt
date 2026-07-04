package `in`.jphe.storyvox.source.primegaming.net

/**
 * Issue #1494 — typed view of the LootScraper "Free Amazon Prime Games" Atom
 * feed. One [PrimeGamingEntry] per claimable title.
 *
 * ## Why hand-rolled regex parsing (same posture as `:source-arxiv`)
 *
 * The feed is a machine-generated Atom 1.0 document with a stable shape
 * (LootScraper renders it from a template). A regex pass over the handful of
 * per-`<entry>` anchors is faster than spinning up [android.util.Xml] and — the
 * decisive reason — **works on the plain JUnit classpath with no Robolectric**,
 * which is all a `:source-*` module's unit tests get.
 *
 * ## The xhtml-content trap (#1494 research finding)
 *
 * LootScraper puts the narratable payload in `<content type="xhtml">` as a
 * nested XHTML `<div>`, NOT as element text. An [org.xmlpull.v1.XmlPullParser]
 * `.nextText()` / a `.text` read on that element returns **empty** — the value
 * is child elements, not a text node. Regex-parsing the raw XML string
 * sidesteps this entirely: we capture the whole `<content>…</content>` span and
 * pull the offer window / description / release date straight out of the inner
 * HTML by their `<b>label:</b>` anchors.
 *
 * If LootScraper's template ever shifts we re-key the regexes; the unit tests
 * pin the canonical layout so a silent break surfaces at CI, not on a user's
 * empty chapter list.
 */
internal data class PrimeGamingFeed(
    /** Feed-level `<title>` (e.g. "Free Amazon Prime Games (PC)"). */
    val title: String?,
    /** Feed-level `<updated>` timestamp (ISO-8601). */
    val updated: String?,
    val entries: List<PrimeGamingEntry>,
) {
    companion object {
        private val ENTRY_REGEX =
            Regex("""<entry\b[^>]*>([\s\S]*?)</entry>""", RegexOption.IGNORE_CASE)

        // Feed-level title/updated live BEFORE the first <entry>; slice the head
        // so an entry's own <title>/<updated> can't be mistaken for the feed's.
        private val FEED_TITLE_REGEX =
            Regex("""<title\b[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
        private val FEED_UPDATED_REGEX =
            Regex("""<updated\b[^>]*>([\s\S]*?)</updated>""", RegexOption.IGNORE_CASE)

        // ── per-entry anchors ──────────────────────────────────────────────
        private val ID_REGEX =
            Regex("""<id\b[^>]*>([\s\S]*?)</id>""", RegexOption.IGNORE_CASE)
        private val TITLE_REGEX =
            Regex("""<title\b[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)
        private val UPDATED_REGEX =
            Regex("""<updated\b[^>]*>([\s\S]*?)</updated>""", RegexOption.IGNORE_CASE)

        /** The whole xhtml content span — parsed further for the offer fields. */
        private val CONTENT_REGEX =
            Regex("""<content\b[^>]*>([\s\S]*?)</content>""", RegexOption.IGNORE_CASE)

        /** `<link ... href="…"/>` — the claim link sits at entry level. */
        private val LINK_HREF_REGEX =
            Regex("""<link\b[^>]*\bhref="([^"]+)"""", RegexOption.IGNORE_CASE)
        /** `<a href="…luna.amazon.com/claims…">` inside the content, as fallback. */
        private val ANCHOR_HREF_REGEX =
            Regex("""<a\b[^>]*\bhref="([^"]+)"""", RegexOption.IGNORE_CASE)

        /** `<category … label="Action"/>` — clean genre labels. */
        private val CATEGORY_LABEL_REGEX =
            Regex("""<category\b[^>]*\blabel="([^"]+)"""", RegexOption.IGNORE_CASE)

        // ── fields inside the xhtml content, keyed off their <b>label:</b> ──
        private val VALID_FROM_REGEX =
            Regex("""valid from:\s*</b>\s*([^<]+)""", RegexOption.IGNORE_CASE)
        private val VALID_TO_REGEX =
            Regex("""valid to:\s*</b>\s*([^<]+)""", RegexOption.IGNORE_CASE)
        private val RELEASE_DATE_REGEX =
            Regex("""release date:\s*</b>\s*([^<]+)""", RegexOption.IGNORE_CASE)
        private val DESCRIPTION_REGEX =
            Regex("""description:\s*</b>\s*([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)
        private val GENRES_INLINE_REGEX =
            Regex("""genres:\s*</b>\s*([\s\S]*?)</li>""", RegexOption.IGNORE_CASE)

        /** Strip the "Amazon Prime (Game) - " / "Amazon Prime (Loot) - " prefix. */
        private val TITLE_PREFIX_REGEX =
            Regex("""^\s*Amazon Prime\s*\([^)]*\)\s*-\s*""", RegexOption.IGNORE_CASE)

        fun parse(xml: String): PrimeGamingFeed {
            val firstEntry = xml.indexOf("<entry", ignoreCase = true)
            val head = if (firstEntry >= 0) xml.substring(0, firstEntry) else xml
            val feedTitle = FEED_TITLE_REGEX.find(head)?.groupValues?.get(1)
                ?.let(::decodeXmlEntities)?.collapseWhitespace()?.ifBlank { null }
            val feedUpdated = FEED_UPDATED_REGEX.find(head)?.groupValues?.get(1)?.trim()
                ?.ifBlank { null }

            val entries = ENTRY_REGEX.findAll(xml)
                .map { parseEntry(it.groupValues[1]) }
                .filter { it.game.isNotBlank() }
                .toList()

            return PrimeGamingFeed(feedTitle, feedUpdated, entries)
        }

        private fun parseEntry(body: String): PrimeGamingEntry {
            val idUrl = ID_REGEX.find(body)?.groupValues?.get(1)?.trim().orEmpty()
            // Stable per-claim id: the trailing LootScraper item number
            // (…/lootscraper/10498 → "10498"). Falls back to a title slug so a
            // template change that drops the numeric id doesn't collapse every
            // chapter onto one key.
            val rawTitle = TITLE_REGEX.find(body)?.groupValues?.get(1)
                ?.let(::decodeXmlEntities)?.collapseWhitespace().orEmpty()
            val game = TITLE_PREFIX_REGEX.replace(rawTitle, "").trim()
            val id = idUrl.substringAfterLast('/', "").ifBlank { slug(game) }

            val updated = UPDATED_REGEX.find(body)?.groupValues?.get(1)?.trim()?.ifBlank { null }

            val content = CONTENT_REGEX.find(body)?.groupValues?.get(1).orEmpty()
            val validFrom = VALID_FROM_REGEX.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val validTo = VALID_TO_REGEX.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val releaseDate = RELEASE_DATE_REGEX.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null }
            val description = DESCRIPTION_REGEX.find(content)?.groupValues?.get(1)
                ?.let(::stripTags)?.let(::decodeXmlEntities)?.collapseWhitespace()?.ifBlank { null }

            // Genres: prefer the <category label="…"> tags (clean); fall back to
            // the inline "Genres: Action, Adventure" list in the content body.
            val categoryGenres = CATEGORY_LABEL_REGEX.findAll(body)
                .map { decodeXmlEntities(it.groupValues[1]).trim() }
                .filter { it.isNotBlank() }
                .toList()
            val genres = categoryGenres.ifEmpty {
                GENRES_INLINE_REGEX.find(content)?.groupValues?.get(1)
                    ?.let(::stripTags)?.let(::decodeXmlEntities)
                    ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                    ?: emptyList()
            }

            // Claim link: the entry-level <link href> pointing at Amazon/Luna,
            // else the first entry <link>, else the <a href> in the content.
            val links = LINK_HREF_REGEX.findAll(body).map { it.groupValues[1] }.toList()
            val claimUrl = links.firstOrNull { it.contains("amazon", true) || it.contains("luna", true) }
                ?: links.firstOrNull()
                ?: ANCHOR_HREF_REGEX.findAll(content).map { it.groupValues[1] }
                    .firstOrNull { it.contains("amazon", true) || it.contains("luna", true) }

            return PrimeGamingEntry(
                id = id,
                game = game,
                validFrom = validFrom,
                validTo = validTo,
                releaseDate = releaseDate,
                description = description,
                genres = genres,
                claimUrl = claimUrl?.let(::decodeXmlEntities),
                updated = updated,
            )
        }

        private fun slug(s: String): String =
            s.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-').ifBlank { "item" }
    }
}

/** One claimable Prime Gaming title (a chapter, in Candela's reading model). */
internal data class PrimeGamingEntry(
    val id: String,
    val game: String,
    val validFrom: String?,
    val validTo: String?,
    val releaseDate: String?,
    val description: String?,
    val genres: List<String>,
    val claimUrl: String?,
    val updated: String?,
)

private val WHITESPACE_RE = Regex("""\s+""")
private val TAG_RE = Regex("""<[^>]+>""")
private val NUMERIC_ENTITY_RE = Regex("""&#(\d+);""")

/** Collapse the multi-line, indented XHTML text into a single spaced line. */
internal fun String.collapseWhitespace(): String = WHITESPACE_RE.replace(trim(), " ")

/** Drop any residual HTML tags left in a captured fragment. */
internal fun stripTags(html: String): String = TAG_RE.replace(html, " ")

/**
 * Decode the entities LootScraper actually emits — named plus numeric
 * (`&#039;` for an apostrophe is common in its game titles). `&amp;` is
 * decoded LAST so a doubly-safe `&amp;lt;` doesn't turn into `<`.
 */
internal fun decodeXmlEntities(text: String): String =
    NUMERIC_ENTITY_RE.replace(text) { m ->
        m.groupValues[1].toIntOrNull()?.let { code -> Char(code).toString() } ?: m.value
    }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
