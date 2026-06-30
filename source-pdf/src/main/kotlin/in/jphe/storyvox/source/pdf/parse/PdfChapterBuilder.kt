package `in`.jphe.storyvox.source.pdf.parse

/**
 * Issue #996 — pure-Kotlin logic that turns extracted [PdfPage]s into
 * an ordered list of [PdfChapter]s. Deliberately Android-free and
 * side-effect-free so it can be unit-tested without Robolectric (the
 * project's JUnit-only, no-Robolectric house style — see CLAUDE.md).
 *
 * Two strategies, chosen automatically:
 *
 *  - **By detected heading** — when a page begins with a line that
 *    looks like a chapter heading ("Chapter 3", "CHAPTER IV", "1.
 *    Introduction", "Part Two"), each such page starts a new chapter
 *    and consumes following heading-less pages. This is the better UX
 *    for born-digital books that carry visible chapter headings.
 *
 *  - **By page batch** — when no headings are detected (typical for
 *    forms, papers, manuals, scanned docs), pages are grouped into
 *    fixed-size batches so the chapter list stays navigable rather than
 *    one giant chapter or hundreds of one-page chapters.
 *
 * Pages whose [PdfPage.text] is blank (image-only page, OCR
 * unavailable) are skipped from chapter bodies but still counted for
 * page-range labels, so a 10-page PDF with 3 scanned pages still reads
 * the 7 it can.
 */
object PdfChapterBuilder {

    /** Pages per chapter when falling back to batch grouping. Tuned so
     *  a typical syllabus / paper (5–30 pages) lands as a handful of
     *  navigable chapters rather than one wall of text. */
    const val DEFAULT_PAGES_PER_CHAPTER: Int = 5

    /**
     * Heading detector. Matches the common chapter / part / numbered-
     * section forms at the very start of a page's text:
     *  - "Chapter 12", "CHAPTER XII", "Chapter Twelve: Foo"
     *  - "Part 2", "PART II"
     *  - "12. Introduction" / "12 Introduction" (leading numbered heading)
     *  - "Section 3"
     */
    private val HEADING_REGEX = Regex(
        "^\\s*(chapter|part|section)\\b.{0,80}" +
            "|^\\s*\\d{1,3}[.)]?\\s+\\p{Lu}.{0,80}",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun build(
        pages: List<PdfPage>,
        pagesPerChapter: Int = DEFAULT_PAGES_PER_CHAPTER,
    ): List<PdfChapter> {
        if (pages.isEmpty()) return emptyList()
        val ordered = pages.sortedBy { it.index }
        val headingPages = ordered.filter { hasHeading(it.text) }
        // Use heading splitting only when headings are present but not on
        // (almost) every page — a heading on every page usually means our
        // detector is over-firing on running headers, in which case batch
        // grouping is more honest.
        val headingFraction = headingPages.size.toDouble() / ordered.size
        return if (headingPages.isNotEmpty() && headingFraction <= 0.6) {
            buildByHeading(ordered)
        } else {
            buildByBatch(ordered, pagesPerChapter.coerceAtLeast(1))
        }
    }

    /** True if the first non-blank line of [text] looks like a heading. */
    fun hasHeading(text: String): Boolean {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return false
        return HEADING_REGEX.containsMatchIn(firstLine)
    }

    /** Extract the heading text (first non-blank line) for a title. */
    fun headingTitle(text: String): String? {
        val firstLine = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: return null
        return if (HEADING_REGEX.containsMatchIn(firstLine)) {
            firstLine.take(80)
        } else {
            null
        }
    }

    private fun buildByHeading(pages: List<PdfPage>): List<PdfChapter> {
        data class Group(val firstPageIndex: Int, val title: String, val pages: MutableList<PdfPage>)

        val groups = mutableListOf<Group>()
        for (page in pages) {
            val heading = headingTitle(page.text)
            if (heading != null || groups.isEmpty()) {
                groups += Group(
                    firstPageIndex = page.index,
                    title = heading ?: "Page ${page.index + 1}",
                    pages = mutableListOf(page),
                )
            } else {
                groups.last().pages += page
            }
        }
        return groups.mapIndexed { idx, g ->
            PdfChapter(
                id = "page-${g.firstPageIndex}",
                title = g.title,
                index = idx,
                firstPageIndex = g.firstPageIndex,
                plainBody = joinPages(g.pages),
            )
        }.filterBlankBodies()
    }

    private fun buildByBatch(pages: List<PdfPage>, pagesPerChapter: Int): List<PdfChapter> {
        return pages.chunked(pagesPerChapter).mapIndexed { idx, chunk ->
            val first = chunk.first().index
            val last = chunk.last().index
            val title = if (first == last) "Page ${first + 1}" else "Pages ${first + 1}–${last + 1}"
            PdfChapter(
                id = "page-$first",
                title = title,
                index = idx,
                firstPageIndex = first,
                plainBody = joinPages(chunk),
            )
        }.filterBlankBodies()
    }

    /** Join a chapter's pages into one plain-text body, separating
     *  pages with a blank line and dropping blank (image-only/no-OCR)
     *  pages from the body. Each page is [reflowPdfText]-reflowed first
     *  (#1428) so PdfBox's per-visual-line newlines don't survive as bad
     *  mid-paragraph breaks in the narration / reader. Page boundaries
     *  stay paragraph breaks — a paragraph that spills across a page
     *  break is split there, matching the pre-existing page-join contract. */
    private fun joinPages(pages: List<PdfPage>): String =
        pages.map { reflowPdfText(it.text) }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    /** Drop chapters that ended up with no narratable text (every page
     *  in the group was image-only and OCR was unavailable), then
     *  re-index so [PdfChapter.index] stays contiguous. */
    private fun List<PdfChapter>.filterBlankBodies(): List<PdfChapter> =
        filter { it.plainBody.isNotBlank() }
            .mapIndexed { idx, ch -> ch.copy(index = idx) }
}

/**
 * Issue #1428 — reflow one PDF page's extracted text so that *soft* line
 * breaks (the per-visual-line `\n`s PdfBox emits for every wrapped line of
 * a paragraph) merge back into spaces, while *real* structural breaks are
 * preserved.
 *
 * The app-side [PdfTextProvider] runs PdfBox's `PDFTextStripper` with
 * default settings, which ends every laid-out line with `\n` — so a
 * paragraph wrapped over five lines arrives as five lines glued by hard
 * newlines. Fed straight to TTS (via `plainBody`) that narrates with broken
 * cadence, and `toParagraphHtml` only splits on blank lines so the stray
 * newlines linger inside each `<p>`. Reflowing here — the one place page
 * text becomes a chapter body — fixes narration and reader in a single
 * pure, unit-testable step.
 *
 * Breaks PRESERVED as paragraph boundaries:
 *  - a blank line (PdfBox emits one where there's extra vertical leading,
 *    i.e. between paragraphs);
 *  - a line beginning a bullet / numbered list item;
 *  - an indented line that follows a completed sentence (first-line-indent
 *    paragraph style).
 *
 * Breaks MERGED to a single space: every other newline between two
 * non-blank lines. A line ending in `-` after a letter is rejoined with the
 * next line — the hyphen dropped when the next line starts lowercase
 * (justified-wrap hyphenation), kept when it starts upper/digit (a real
 * compound such as "Anglo-Saxon"). The rare compound that wraps exactly at
 * its hyphen with a lowercase tail (e.g. "well-known") loses the hyphen —
 * an accepted trade for fixing the common justified-wrap case.
 *
 * Prose-optimized: meaningful line breaks in poetry / tables / addresses
 * are reflowed too. That is the intended direction for a TTS audiobook
 * source where the reported defect is *too many* breaks, and the blank-line
 * + list heuristics still preserve coarse structure.
 */
internal fun reflowPdfText(raw: String): String {
    if (raw.isBlank()) return ""
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val paragraphs = mutableListOf<String>()
    val buf = StringBuilder()

    fun flush() {
        if (buf.isNotBlank()) paragraphs += buf.toString()
        buf.setLength(0)
    }

    for (rawLine in lines) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            flush() // blank line → paragraph boundary (collapses runs of blanks)
            continue
        }
        if (buf.isNotEmpty() && startsNewParagraph(rawLine, line, buf)) flush()
        if (buf.isEmpty()) buf.append(line) else appendWrapped(buf, line)
    }
    flush()

    return paragraphs.joinToString("\n\n") { it.replace(MULTI_SPACE, " ").trim() }
}

private val MULTI_SPACE = Regex("[ \\t]+")

/** Bullet (•, ◦, ▪, ‣, *) or dash/en/em-dash list marker, or a numbered
 *  item ("1.", "12)") at the very start of a (trimmed) line. */
private val LIST_START = Regex("^([\\u2022\\u25E6\\u25AA\\u2023*]\\s*|[-\\u2013\\u2014]\\s+|\\d{1,3}[.)]\\s+)")

/** A non-blank line begins a new paragraph (despite no preceding blank
 *  line) when it opens a list item, or when it is indented and the text so
 *  far ended a sentence — the first-line-indent paragraph convention. */
private fun startsNewParagraph(rawLine: String, trimmed: String, buf: StringBuilder): Boolean {
    if (LIST_START.containsMatchIn(trimmed)) return true
    val indent = rawLine.length - rawLine.trimStart().length
    return indent >= 2 && endsSentence(buf)
}

/** True when the buffered text's last visible character is sentence-final
 *  (`.`/`!`/`?`), allowing one trailing closing quote or paren. */
private fun endsSentence(buf: CharSequence): Boolean {
    var i = buf.length - 1
    while (i >= 0 && buf[i].isWhitespace()) i--
    if (i >= 0 && (buf[i] == '"' || buf[i] == '\'' || buf[i] == '”' || buf[i] == '’' || buf[i] == ')' || buf[i] == ']')) i--
    if (i < 0) return false
    return buf[i] == '.' || buf[i] == '!' || buf[i] == '?'
}

/** Append a wrapped continuation line to the paragraph buffer, handling
 *  end-of-line hyphenation (see [reflowPdfText] kdoc). */
private fun appendWrapped(buf: StringBuilder, next: String) {
    val n = buf.length
    val endsHyphen = n >= 2 && buf[n - 1] == '-' && buf[n - 2].isLetter()
    when {
        endsHyphen && next.firstOrNull()?.isLowerCase() == true -> {
            buf.setLength(n - 1) // soft hyphen from a line-wrap → de-hyphenate
            buf.append(next)
        }
        endsHyphen -> buf.append(next) // keep the hyphen (compound/dash), no inserted space
        else -> buf.append(' ').append(next)
    }
}
