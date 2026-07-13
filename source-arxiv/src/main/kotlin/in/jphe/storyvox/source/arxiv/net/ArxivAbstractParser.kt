package `in`.jphe.storyvox.source.arxiv.net

import `in`.jphe.storyvox.data.text.htmlToInlineText

/**
 * Issue #378 — focused regex extractor over the arXiv abstract page
 * (`arxiv.org/abs/<id>`). Pulls a small, narrating-friendly set of
 * fields out of the server-rendered HTML so the chapter body reads
 * cleanly through TTS:
 *
 *  - **Title** (`<meta name="citation_title">` or the `<h1 class="title">`
 *    block). The citation meta tag is the most machine-stable surface.
 *  - **Authors** (`<meta name="citation_author">` repeated per author,
 *    or the `<div class="authors">` link list).
 *  - **Abstract** (`<meta name="citation_abstract">` or the
 *    `<blockquote class="abstract">` block).
 *  - **Subjects** (`<td class="tablecell subjects">`) — the comma-list
 *    of arXiv categories, useful in the chapter intro.
 *  - **Comments** (`<td class="tablecell comments">`) — author-supplied
 *    notes (page count, conference accepted-at, ...). Optional.
 *
 * ## Why regex over jsoup
 *
 * Same posture as `:source-standard-ebooks`'s SeHtmlParser — arXiv's
 * abstract pages have a stable, decade-old layout; the `<meta
 * name="citation_*">` tags are part of arXiv's published metadata
 * contract (they're how Google Scholar indexes papers). Regex over
 * those anchors is plenty robust and runs on the plain JUnit classpath.
 *
 * Field text (tag-stripping + full entity decode + whitespace collapse)
 * goes through the shared [htmlToInlineText] in core-data (#1628) — one
 * entity table for the whole app, covering the hex refs / accents / curly
 * quotes the old local decoder missed. jsoup stays transitive to core-data;
 * this module adds no jsoup dependency of its own.
 */
internal object ArxivAbstractParser {

    /** `<meta name="citation_title" content="...">` — capture content. */
    private val META_TITLE_REGEX = Regex(
        """<meta\b[^>]*\bname="citation_title"[^>]*\bcontent="([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    /** `<meta name="citation_author" content="Last, First">` — repeated.
     *  arXiv emits one per author in document order. */
    private val META_AUTHOR_REGEX = Regex(
        """<meta\b[^>]*\bname="citation_author"[^>]*\bcontent="([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    /** `<meta name="citation_abstract" content="...">`. arXiv emits the
     *  abstract here on the abs/<id> page; multi-paragraph abstracts
     *  arrive as one line with embedded newlines. */
    private val META_ABSTRACT_REGEX = Regex(
        """<meta\b[^>]*\bname="citation_abstract"[^>]*\bcontent="([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    /** Fallback abstract block when the meta tag is missing — `<blockquote
     *  class="abstract"><span class="descriptor">Abstract:</span>...</blockquote>`. */
    private val ABSTRACT_BLOCK_REGEX = Regex(
        """<blockquote\b[^>]*\bclass="[^"]*\babstract\b[^"]*"[^>]*>([\s\S]*?)</blockquote>""",
        RegexOption.IGNORE_CASE,
    )

    /** Fallback title block — `<h1 class="title mathjax"><span ...>Title:</span> Real Title</h1>`. */
    private val H1_TITLE_REGEX = Regex(
        """<h1\b[^>]*\bclass="[^"]*\btitle\b[^"]*"[^>]*>([\s\S]*?)</h1>""",
        RegexOption.IGNORE_CASE,
    )

    /** Subjects row — `<td class="tablecell subjects">cs.AI; cs.CL</td>`. */
    private val SUBJECTS_REGEX = Regex(
        """<td\b[^>]*\bclass="[^"]*\bsubjects\b[^"]*"[^>]*>([\s\S]*?)</td>""",
        RegexOption.IGNORE_CASE,
    )

    /** Comments row — author-supplied annotation
     *  (`8 pages, 3 figures, accepted at NeurIPS 2025`). */
    private val COMMENTS_REGEX = Regex(
        """<td\b[^>]*\bclass="[^"]*\bcomments\b[^"]*"[^>]*>([\s\S]*?)</td>""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Parse a fetched abstract-page HTML string into a structured
     * [ArxivAbstract]. Missing fields become empty/null; the chapter
     * builder fills sensible defaults so a partial parse still renders.
     */
    fun parse(html: String): ArxivAbstract {
        val title = META_TITLE_REGEX.find(html)?.groupValues?.get(1)
            ?.htmlToInlineText()
            ?: H1_TITLE_REGEX.find(html)?.groupValues?.get(1)
                ?.htmlToInlineText()
                // The "Title:" descriptor-span label survives as text and
                // comes off AFTER whitespace collapse (the leading indent
                // would otherwise prevent the prefix match).
                ?.removePrefix("Title:")
                ?.trim()
            ?: ""

        val authors = META_AUTHOR_REGEX.findAll(html)
            .map { it.groupValues[1].htmlToInlineText() }
            .filter { it.isNotBlank() }
            .toList()

        val abstract = META_ABSTRACT_REGEX.find(html)?.groupValues?.get(1)
            ?.htmlToInlineText()
            ?: ABSTRACT_BLOCK_REGEX.find(html)?.groupValues?.get(1)
                ?.htmlToInlineText()
                // Strip the leading "Abstract:" label AFTER whitespace
                // collapses — same shape as the title fallback above.
                ?.removePrefix("Abstract:")
                ?.trim()
            ?: ""

        val subjects = SUBJECTS_REGEX.find(html)?.groupValues?.get(1)
            ?.htmlToInlineText()
            .orEmpty()

        val comments = COMMENTS_REGEX.find(html)?.groupValues?.get(1)
            ?.htmlToInlineText()
            ?.takeIf { it.isNotBlank() }

        return ArxivAbstract(
            title = title,
            authors = authors,
            abstract = abstract,
            subjects = subjects,
            comments = comments,
        )
    }

    /**
     * Compose the narrating-friendly chapter body. Reads as one
     * coherent paragraph: title (already in the fiction summary, but
     * worth restating for cold-listen context), author list spoken as
     * "by A, B, and C", subjects, optional author comments, then the
     * abstract proper.
     *
     * Returned HTML is intentionally minimal — `<p>` wrappers only,
     * no inline formatting — because the playback-side TTS pipeline
     * does the heavy lifting. Same shape as the Wikipedia chapter
     * cleanup output.
     */
    fun toChapterHtml(abstract: ArxivAbstract): String = buildString {
        if (abstract.title.isNotBlank()) {
            append("<h2>").append(escapeHtml(abstract.title)).append("</h2>")
        }
        val byline = formatAuthors(abstract.authors)
        if (byline.isNotBlank()) {
            append("<p><em>by ").append(escapeHtml(byline)).append("</em></p>")
        }
        if (abstract.subjects.isNotBlank()) {
            append("<p><strong>Subjects:</strong> ")
                .append(escapeHtml(abstract.subjects))
                .append("</p>")
        }
        abstract.comments?.let {
            append("<p><strong>Comments:</strong> ")
                .append(escapeHtml(it))
                .append("</p>")
        }
        if (abstract.abstract.isNotBlank()) {
            append("<p><strong>Abstract.</strong> ")
                .append(escapeHtml(abstract.abstract))
                .append("</p>")
        }
    }

    /** Plain-text rendering for the TTS pipeline. Mirrors the HTML
     *  structure with explicit "Subjects:" / "Comments:" /
     *  "Abstract." prefixes that read as natural sentences. */
    fun toChapterPlain(abstract: ArxivAbstract): String = buildString {
        if (abstract.title.isNotBlank()) {
            append(abstract.title).append("\n\n")
        }
        val byline = formatAuthors(abstract.authors)
        if (byline.isNotBlank()) {
            append("by ").append(byline).append(".\n\n")
        }
        if (abstract.subjects.isNotBlank()) {
            append("Subjects: ").append(abstract.subjects).append(".\n\n")
        }
        abstract.comments?.let {
            append("Comments: ").append(it).append(".\n\n")
        }
        if (abstract.abstract.isNotBlank()) {
            append("Abstract. ").append(abstract.abstract)
        }
    }.trim()

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
}

/**
 * Structured view of an arXiv abstract page. Most fields are required
 * on a happy-path parse; [comments] is nullable because it's optional
 * on arXiv (only present when the author supplied annotation text).
 */
internal data class ArxivAbstract(
    val title: String,
    val authors: List<String>,
    val abstract: String,
    val subjects: String,
    val comments: String? = null,
)

/**
 * Render an author list as "A", "A and B", "A, B, and C", or "" for
 * the empty case. Oxford comma — keeps the spoken sentence parseable
 * when one of the authors has a comma in their display name (rare but
 * does happen with academic conventions like "Smith, John Q.").
 */
internal fun formatAuthors(authors: List<String>): String = when (authors.size) {
    0 -> ""
    1 -> authors[0]
    2 -> "${authors[0]} and ${authors[1]}"
    else -> authors.dropLast(1).joinToString(", ") + ", and ${authors.last()}"
}
