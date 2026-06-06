package `in`.jphe.storyvox.feature.reader

/**
 * Issue #998 — find-in-text within the loaded chapter. Pure, Compose-free
 * so the match-finding core is unit-testable without rendering the reader
 * (mirrors #794's `filterChaptersByQuery`).
 *
 * Scans [text] for every non-overlapping, case-insensitive occurrence of
 * [query] and returns their UTF-16 char ranges in document order. The
 * ranges are end-inclusive (`start..end`) so a caller can feed
 * `range.first` straight to `TextLayoutResult.getLineForOffset(...)` for a
 * jump-to-match scroll, or pass the range to the search-highlight overlay.
 *
 * A blank / empty query (or empty text) returns an empty list — there's
 * nothing to highlight or count. The query is trimmed first so a stray
 * paste space doesn't zero out results, matching #794.
 *
 * Non-overlapping: after a hit at index `i` of a `len`-char needle, the
 * scan resumes at `i + len`, so "aa" in "aaaa" yields 0..1 and 2..3, not
 * a third spurious 1..2. This is the right contract for highlighting —
 * overlapping spans would double-paint the same glyphs.
 */
internal fun findMatches(text: String, query: String): List<IntRange> {
    val needle = query.trim()
    if (needle.isEmpty() || text.isEmpty()) return emptyList()

    val matches = mutableListOf<IntRange>()
    var from = 0
    while (from <= text.length - needle.length) {
        val hit = text.indexOf(needle, startIndex = from, ignoreCase = true)
        if (hit < 0) break
        matches.add(hit..(hit + needle.length - 1))
        from = hit + needle.length
    }
    return matches
}

/**
 * Issue #998 — advance to the next match, wrapping from the last hit back
 * to the first. Total over [count] == 0 (returns 0) so the next/prev
 * chevrons can call it unconditionally even before any query is entered.
 */
internal fun nextMatchIndex(current: Int, count: Int): Int =
    if (count <= 0) 0 else (current + 1) % count

/**
 * Issue #998 — retreat to the previous match, wrapping from the first hit
 * back to the last. `+ count` before the modulo keeps the result
 * non-negative when [current] is 0.
 */
internal fun prevMatchIndex(current: Int, count: Int): Int =
    if (count <= 0) 0 else (current - 1 + count) % count
