package `in`.jphe.storyvox.source.arxiv.net

import `in`.jphe.storyvox.data.text.htmlToInlineText

/**
 * Issue #378 — typed view of an arXiv API Atom feed response from
 * `export.arxiv.org/api/query`. arXiv returns a standard Atom 1.0
 * document with `<entry>` per paper carrying `<title>`, `<summary>`
 * (abstract), `<author><name/></author>` blocks, `<id>` (URL like
 * `http://arxiv.org/abs/2401.12345v1`), and a `<link rel="alternate"
 * type="text/html" href=".../abs/<id>">` plus `<link rel="related"
 * title="pdf" href=".../pdf/<id>.pdf">`.
 *
 * ## Why hand-rolled regex parsing
 *
 * Same posture as `:source-standard-ebooks`'s SeHtmlParser — arXiv's
 * Atom feeds are machine-stable (publicly documented at
 * https://info.arxiv.org/help/api/user-manual.html). A regex pass over
 * the small set of `<entry>`/`<title>`/`<summary>`/`<author>`/`<id>`/
 * `<link>` anchors is faster than spinning up [android.util.Xml] +
 * works on the plain JUnit test classpath (no Robolectric).
 *
 * If arXiv's feed shape ever shifts we re-key the regexes; the unit
 * tests pin the canonical layout so a silent break surfaces at CI time.
 */
internal data class ArxivAtomFeed(
    val totalResults: Long?,
    val entries: List<ArxivFeedEntry>,
) {
    companion object {
        /**
         * Each entry begins with `<entry>` and ends at `</entry>`. arXiv
         * doesn't nest entries inside other elements at the feed level —
         * they're siblings under `<feed>` — so a non-greedy span suffices.
         */
        private val ENTRY_REGEX = Regex(
            """<entry\b[^>]*>([\s\S]*?)</entry>""",
            RegexOption.IGNORE_CASE,
        )

        /** OpenSearch extension: `<opensearch:totalResults>N</opensearch:totalResults>`. */
        private val TOTAL_RESULTS_REGEX = Regex(
            """<opensearch:totalResults\b[^>]*>(\d+)</opensearch:totalResults>""",
            RegexOption.IGNORE_CASE,
        )

        private val TITLE_REGEX = Regex(
            """<title\b[^>]*>([\s\S]*?)</title>""",
            RegexOption.IGNORE_CASE,
        )

        private val SUMMARY_REGEX = Regex(
            """<summary\b[^>]*>([\s\S]*?)</summary>""",
            RegexOption.IGNORE_CASE,
        )

        private val ID_REGEX = Regex(
            """<id\b[^>]*>([\s\S]*?)</id>""",
            RegexOption.IGNORE_CASE,
        )

        private val UPDATED_REGEX = Regex(
            """<updated\b[^>]*>([\s\S]*?)</updated>""",
            RegexOption.IGNORE_CASE,
        )

        private val PUBLISHED_REGEX = Regex(
            """<published\b[^>]*>([\s\S]*?)</published>""",
            RegexOption.IGNORE_CASE,
        )

        /** `<author><name>...</name></author>` — strip the wrapper, capture the name. */
        private val AUTHOR_NAME_REGEX = Regex(
            """<author\b[^>]*>[\s\S]*?<name\b[^>]*>([\s\S]*?)</name>[\s\S]*?</author>""",
            RegexOption.IGNORE_CASE,
        )

        /** `<category term="cs.AI" scheme="..."/>` — capture the term. */
        private val CATEGORY_REGEX = Regex(
            """<category\b[^>]*\bterm="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        /** `<link rel="alternate" ... href="..." />` — the abstract page URL. */
        private val LINK_ALT_REGEX = Regex(
            """<link\b[^>]*\brel="alternate"[^>]*\bhref="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        /** `<link ... title="pdf" href="..." />` — the PDF link. */
        private val LINK_PDF_REGEX = Regex(
            """<link\b[^>]*\btitle="pdf"[^>]*\bhref="([^"]+)"""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Parse an Atom feed XML string. Returns an empty feed on
         * unrecognised input (the source layer maps that to an empty
         * list rather than a parse error — arXiv occasionally returns
         * an empty `<feed>` for esoteric category queries and an empty
         * result is the right UX, not a failure dialog).
         */
        fun parse(xml: String): ArxivAtomFeed {
            val total = TOTAL_RESULTS_REGEX.find(xml)?.groupValues?.get(1)?.toLongOrNull()
            val entries = ENTRY_REGEX.findAll(xml).map { match ->
                parseEntry(match.groupValues[1])
            }.filter { it.arxivId.isNotBlank() }.toList()
            return ArxivAtomFeed(totalResults = total, entries = entries)
        }

        private fun parseEntry(body: String): ArxivFeedEntry {
            val title = TITLE_REGEX.find(body)?.groupValues?.get(1)
                ?.htmlToInlineText()
                .orEmpty()

            val summary = SUMMARY_REGEX.find(body)?.groupValues?.get(1)
                ?.htmlToInlineText()
                .orEmpty()

            val id = ID_REGEX.find(body)?.groupValues?.get(1)?.trim().orEmpty()
            // `<id>http://arxiv.org/abs/2401.12345v1</id>` — pull the
            // bare arxiv id from the tail; version suffixes (v1, v2...)
            // are stripped because the abstract page resolves to the
            // current revision automatically.
            val arxivId = extractArxivId(id)

            val updated = UPDATED_REGEX.find(body)?.groupValues?.get(1)?.trim()
            val published = PUBLISHED_REGEX.find(body)?.groupValues?.get(1)?.trim()

            val authors = AUTHOR_NAME_REGEX.findAll(body)
                .map { it.groupValues[1].htmlToInlineText() }
                .filter { it.isNotBlank() }
                .toList()

            val categories = CATEGORY_REGEX.findAll(body)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()

            val absUrl = LINK_ALT_REGEX.find(body)?.groupValues?.get(1)
            val pdfUrl = LINK_PDF_REGEX.find(body)?.groupValues?.get(1)

            return ArxivFeedEntry(
                arxivId = arxivId,
                title = title,
                summary = summary,
                authors = authors,
                categories = categories,
                updated = updated,
                published = published,
                absUrl = absUrl,
                pdfUrl = pdfUrl,
            )
        }
    }
}

/**
 * One paper. Mirrors what we need from the Atom `<entry>`. `arxivId` is
 * the canonical id without a version suffix (e.g. "2401.12345" or the
 * pre-2007 "cs/0703021" form) — the abstract page at
 * `arxiv.org/abs/<arxivId>` always resolves to the latest revision.
 */
internal data class ArxivFeedEntry(
    val arxivId: String,
    val title: String,
    val summary: String,
    val authors: List<String>,
    val categories: List<String>,
    val updated: String? = null,
    val published: String? = null,
    val absUrl: String? = null,
    val pdfUrl: String? = null,
)

/**
 * Pull the canonical arXiv id out of a `<id>` URL. arXiv emits ids like
 *  - `http://arxiv.org/abs/2401.12345v1` (post-2007 new-style)
 *  - `http://arxiv.org/abs/cs/0703021v1` (pre-2007 archive-style with
 *    a slash in the id)
 *
 * Strips the version suffix (`v1`, `v2`, ...) — the abstract page
 * resolves bare id → latest revision automatically. Returns "" on
 * inputs that don't look like an arXiv abs URL so the parser drops
 * them from the result list.
 */
internal fun extractArxivId(idUrl: String): String {
    if (idUrl.isBlank()) return ""
    val tail = idUrl.substringAfter("/abs/", missingDelimiterValue = "")
    if (tail.isEmpty()) return ""
    // Strip trailing version suffix; keep any slash in archive-style ids.
    return tail.replace(Regex("v\\d+$"), "")
}
