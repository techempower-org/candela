package `in`.jphe.storyvox.source.audiobook.writer

/**
 * Splits a blob of pasted / imported plain text into chapters for the
 * "Make your own audiobook" flow (issue #1003).
 *
 * The split heuristic, in priority order:
 *  1. **Explicit chapter headings** — lines like `Chapter 1`, `CHAPTER IV`,
 *     `Chapter One:`, `Part 2`, or a bare `# Markdown heading`. When at least
 *     two such headings are found, each heading opens a new chapter and the
 *     heading line becomes that chapter's title.
 *  2. **Form-feed / horizontal-rule breaks** — a line that's only `---`,
 *     `***`, `===`, or a literal form-feed (`\f`, common in `.txt` exports
 *     from PDFs) splits chapters. Titles fall back to "Chapter N".
 *  3. **Fallback: one chapter.** Short pastes (a single article, a poem)
 *     become a single chapter titled after the book.
 *
 * This is deliberately a pure function over a `String` so it unit-tests
 * without Android. The UI lets the user edit the result before rendering
 * (manual-edit affordance per the issue), so the heuristic only needs to be
 * a sensible *default*, not perfect.
 */
object Chapterizer {

    /** A chapter heading: optional markdown hashes, then `Chapter`/`Part`/
     *  `Section` + a number (arabic or roman) or spelled-out number, with an
     *  optional `: Subtitle`. Case-insensitive, whole line. */
    private val HEADING = Regex(
        pattern = """^\s*#{0,6}\s*(chapter|part|section|book)\s+""" +
            """([0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|""" +
            """eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|""" +
            """nineteen|twenty)\b.*$""",
        options = setOf(RegexOption.IGNORE_CASE),
    )

    /** A bare markdown heading line (`# Title`, `## Title`) used as a chapter
     *  break when no `Chapter N` headings are present. */
    private val MD_HEADING = Regex("""^\s*#{1,6}\s+\S.*$""")

    /** A thematic-break line: only rule characters (`---`, `***`, `___`,
     *  `===`). */
    private val RULE = Regex("""^\s*([-*_=]\s*){3,}\s*$""")

    /** Canonical rule line a form-feed is rewritten to before splitting. */
    private const val RULE_LINE = "---"

    /** Form-feed: a page break, common in `.txt` exports of paginated docs. */
    private const val FORM_FEED = '\u000C'

    /**
     * Split [fullText] into chapters. [bookTitle] is used as the single
     * chapter's title in the fallback (no-heading) case. Never returns an
     * empty list for non-blank input; blank input yields an empty list.
     */
    fun chapterize(fullText: String, bookTitle: String): List<AudiobookChapter> {
        // Normalize newlines, and promote any form-feed page break to a
        // standalone thematic-break line so the line-oriented splitters below
        // see it cleanly regardless of the text that surrounds it on the page.
        val normalized = fullText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(FORM_FEED.toString(), "\n$RULE_LINE\n")
        if (normalized.isBlank()) return emptyList()

        // 1. Try explicit Chapter/Part headings.
        byHeadings(normalized, HEADING)?.let { return it }
        // 2. Fall back to markdown headings (must have at least 2 to be a
        //    structure rather than a single titled article).
        byHeadings(normalized, MD_HEADING, minHeadings = 2)?.let { return it }
        // 3. Thematic-break splits.
        byRules(normalized)?.let { return it }
        // 4. Single chapter.
        return listOf(
            AudiobookChapter(
                title = bookTitle.ifBlank { "Chapter 1" },
                text = normalized.trim(),
            ),
        )
    }

    /**
     * Split where lines match [heading]. Returns null when fewer than
     * [minHeadings] headings are present (caller falls through to the next
     * strategy). Any text *before* the first heading becomes an untitled
     * "Foreword"-style chapter so no content is dropped.
     */
    private fun byHeadings(
        text: String,
        heading: Regex,
        minHeadings: Int = 2,
    ): List<AudiobookChapter>? {
        val lines = text.split("\n")
        val headingIdx = lines.indices.filter { heading.matches(lines[it]) }
        if (headingIdx.size < minHeadings) return null

        val chapters = mutableListOf<AudiobookChapter>()

        // Preamble before the first heading (dedication, intro) — keep it.
        val firstHeading = headingIdx.first()
        if (firstHeading > 0) {
            val pre = lines.subList(0, firstHeading).joinToString("\n").trim()
            if (pre.isNotBlank()) {
                chapters += AudiobookChapter(title = "Introduction", text = pre)
            }
        }

        for ((n, start) in headingIdx.withIndex()) {
            val end = headingIdx.getOrNull(n + 1) ?: lines.size
            val rawTitle = lines[start].trim().removePrefix("#").trim()
                .trimStart('#').trim()
                .ifBlank { "Chapter ${n + 1}" }
            val body = lines.subList(start + 1, end).joinToString("\n").trim()
            // Skip a heading with no body (two headings back-to-back) but
            // keep the title carried onto the next non-empty section by
            // simply emitting the (possibly title-only) chapter — players
            // tolerate a near-zero-length chapter, and dropping it would
            // misalign the user's manual edits. We still emit so the count
            // matches what the user sees.
            chapters += AudiobookChapter(title = cleanTitle(rawTitle), text = body)
        }
        return chapters
    }

    /** Split on thematic-break lines. Returns null when there are no rule
     *  lines (so the single-chapter fallback applies). */
    private fun byRules(text: String): List<AudiobookChapter>? {
        val lines = text.split("\n")
        if (lines.none { RULE.matches(it) }) return null

        val chapters = mutableListOf<AudiobookChapter>()
        val current = StringBuilder()
        var chapterNo = 1
        fun flush() {
            val body = current.toString().trim()
            if (body.isNotBlank()) {
                chapters += AudiobookChapter(title = "Chapter $chapterNo", text = body)
                chapterNo++
            }
            current.setLength(0)
        }
        for (line in lines) {
            if (RULE.matches(line)) {
                flush()
            } else {
                current.append(line).append('\n')
            }
        }
        flush()
        return chapters.ifEmpty { null }
    }

    /** Trim a heading down to a clean marker label: collapse whitespace and
     *  cap length so a runaway "heading" (a wrapped paragraph mis-detected as
     *  a heading) doesn't produce an absurd chapter title. */
    private fun cleanTitle(raw: String): String {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        return if (collapsed.length > 80) collapsed.take(77) + "…" else collapsed
    }
}
