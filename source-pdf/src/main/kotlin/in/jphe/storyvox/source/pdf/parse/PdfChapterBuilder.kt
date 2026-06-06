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
     *  pages from the body. */
    private fun joinPages(pages: List<PdfPage>): String =
        pages.map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")

    /** Drop chapters that ended up with no narratable text (every page
     *  in the group was image-only and OCR was unavailable), then
     *  re-index so [PdfChapter.index] stays contiguous. */
    private fun List<PdfChapter>.filterBlankBodies(): List<PdfChapter> =
        filter { it.plainBody.isNotBlank() }
            .mapIndexed { idx, ch -> ch.copy(index = idx) }
}
