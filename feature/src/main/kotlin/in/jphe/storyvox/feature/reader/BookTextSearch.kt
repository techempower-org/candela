package `in`.jphe.storyvox.feature.reader

/**
 * Issue #1229 — whole-book ("find in book") text search core.
 *
 * Pure, Compose-/Room-free so the aggregation + snippet logic is unit-testable
 * without a rendering or database runtime — the same split #998's [findMatches]
 * uses for single-chapter find-in-text. The ViewModel pulls matching chapter
 * bodies from [in.jphe.storyvox.data.repository.ChapterRepository.searchChapterBodies]
 * (a coarse SQL `LIKE` pre-filter) and feeds them here; [searchBook] re-scans
 * each body with the exact, literal [findMatches] pass to compute real match
 * offsets, counts, and a highlighted snippet, dropping any chapter the `LIKE`
 * over-matched.
 */

/** Default context window (chars on each side of the first match) for a result
 *  snippet. ~48 chars/side keeps a result row to roughly one comfortable line. */
internal const val SNIPPET_RADIUS = 48

/**
 * Minimal per-chapter input to [searchBook]. Decoupled from Room / core-data
 * row types so the search core stays free of an Android/Room dependency; the
 * ViewModel maps `ChapterSearchRow → ChapterBody` at the seam.
 */
data class ChapterBody(
    val chapterId: String,
    val index: Int,
    val title: String,
    val body: String,
)

/**
 * One whole-book search hit — a chapter whose body contains the query, ready to
 * render as a result row and to navigate to.
 */
data class BookSearchResult(
    val chapterId: String,
    val chapterIndex: Int,
    val chapterTitle: String,
    /**
     * 0-based char offset of the *first* match in the chapter's plain body.
     * Fed straight to `PlaybackControllerUi.startListening(charOffset = …)`
     * for jump-to-match (the body offset and the playback text offset are the
     * same string — the player speaks `plainBody`).
     */
    val matchOffset: Int,
    /** Total non-overlapping matches in this chapter (drives the count badge). */
    val matchCount: Int,
    /** One-line context window of the body around the first match. */
    val snippet: String,
    /**
     * End-inclusive range of the query *within* [snippet] — for the result
     * row's highlight span. Inclusive to match [findMatches]'s convention; the
     * renderer adds +1 for the exclusive `AnnotatedString` span end.
     */
    val snippetMatchRange: IntRange,
)

private val WHITESPACE_RUN = Regex("\\s+")

/** Collapse every run of whitespace (incl. newlines) to a single space so a
 *  raw `plainBody` slice reads as one snippet line. */
private fun collapseWhitespace(s: String): String = s.replace(WHITESPACE_RUN, " ")

/**
 * Build a one-line context snippet around the match at [matchStart]
 * (length [matchLength]) in [body], plus the match's end-inclusive range
 * *within the returned snippet* so the result row can paint a highlight.
 *
 * Whitespace is collapsed segment-by-segment (before / matched / after) rather
 * than over the whole slice, so collapsing newlines never desyncs the highlight
 * range from the visible text. A leading "…" / trailing "…" marks a clipped
 * window; the boundary segments are trimmed on their outer edges so the snippet
 * never reads as "… word".
 */
internal fun buildSnippet(
    body: String,
    matchStart: Int,
    matchLength: Int,
    radius: Int = SNIPPET_RADIUS,
): Pair<String, IntRange> {
    if (body.isEmpty() || matchLength <= 0) return "" to IntRange.EMPTY
    val start = matchStart.coerceIn(0, body.length)
    val matchEnd = (matchStart + matchLength).coerceIn(start, body.length)
    val from = (start - radius).coerceAtLeast(0)
    val to = (matchEnd + radius).coerceAtMost(body.length)

    val before = collapseWhitespace(body.substring(from, start)).trimStart()
    val matched = collapseWhitespace(body.substring(start, matchEnd))
    val after = collapseWhitespace(body.substring(matchEnd, to)).trimEnd()

    val left = if (from > 0) "…" else ""
    val right = if (to < body.length) "…" else ""

    val snippet = left + before + matched + after + right
    val rangeStart = left.length + before.length
    val rangeEnd = (rangeStart + matched.length - 1).coerceAtLeast(rangeStart)
    return snippet to (rangeStart..rangeEnd)
}

/**
 * Scan each chapter [body] for [query] and return one [BookSearchResult] per
 * chapter that genuinely contains it, in the order the chapters were supplied
 * (the repository hands them over in reading order). A blank query — or a
 * chapter the SQL `LIKE` over-matched but that holds no literal occurrence —
 * yields nothing for that input.
 *
 * The query is trimmed once here (and again, harmlessly, inside [findMatches])
 * so a stray paste space doesn't zero the result set, matching #998 / #794.
 */
internal fun searchBook(
    chapters: List<ChapterBody>,
    query: String,
    radius: Int = SNIPPET_RADIUS,
): List<BookSearchResult> {
    val needle = query.trim()
    if (needle.isEmpty()) return emptyList()
    val results = ArrayList<BookSearchResult>()
    for (chapter in chapters) {
        val matches = findMatches(chapter.body, needle)
        if (matches.isEmpty()) continue
        val first = matches.first()
        val (snippet, range) = buildSnippet(
            body = chapter.body,
            matchStart = first.first,
            matchLength = first.last - first.first + 1,
            radius = radius,
        )
        results += BookSearchResult(
            chapterId = chapter.chapterId,
            chapterIndex = chapter.index,
            chapterTitle = chapter.title,
            matchOffset = first.first,
            matchCount = matches.size,
            snippet = snippet,
            snippetMatchRange = range,
        )
    }
    return results
}
