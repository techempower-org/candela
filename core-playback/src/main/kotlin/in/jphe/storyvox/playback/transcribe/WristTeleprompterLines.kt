package `in`.jphe.storyvox.playback.transcribe

/**
 * Issue #1372/#1308 follow-up — turns the voice-paced [VoicePacedScrollController]'s
 * character offset into the **current + next display line** for a glanceable
 * surface (the Wear teleprompter remote). Pure (no Android, no Compose) so the
 * line-splitting + lookup are unit-testable, and surface-agnostic: it produces
 * *text*, not pixels (the px mapping stays the reader's job — see the
 * VoicePacedScrollController kdoc).
 *
 * The watch can't hold the chapter text (too large to sync over the Wearable
 * data layer), so the phone derives these two short strings from `positionChar`
 * and ships only them in `PlaybackState`.
 */

/** A line of the chapter with its character span in the original text. */
data class LineSpan(val start: Int, val end: Int, val text: String)

/** The line the speaker is currently on plus the upcoming one (either may be
 *  empty — e.g. no text, or the last line has no successor). */
data class WristLines(val current: String, val next: String) {
    companion object {
        val EMPTY = WristLines("", "")
    }
}

/**
 * Split [text] into teleprompter "lines" — sentence/line units broken at
 * terminal punctuation (`.`, `!`, `?`, `…`) or newlines, with their spans in
 * the original string preserved so a [positionChar] can be located. Runs of
 * terminators/newlines (e.g. `"..."`, `".\n\n"`) collapse into one break, and
 * whitespace-only fragments are dropped, so a script's blank lines and ellipses
 * don't produce empty "lines".
 */
fun splitTeleprompterLines(text: String): List<LineSpan> {
    val out = ArrayList<LineSpan>()
    var start = 0
    var i = 0

    fun isBreak(c: Char) = c == '\n' || c == '.' || c == '!' || c == '?' || c == '…'

    fun flush(end: Int) {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        // Keep only spans that contain at least one letter/digit — drops
        // pure-punctuation crumbs left by a run of terminators.
        if (e > s && (s until e).any { text[it].isLetterOrDigit() }) {
            out.add(LineSpan(s, e, text.substring(s, e)))
        }
        start = end
    }

    while (i < text.length) {
        if (isBreak(text[i])) {
            var j = i + 1
            while (j < text.length && isBreak(text[j])) j++
            flush(j)
            i = j
        } else {
            i++
        }
    }
    if (start < text.length) flush(text.length)
    return out
}

/**
 * The [WristLines] for [positionChar] against pre-split [spans]: the current
 * line is the last span starting at or before [positionChar] (so a cursor
 * mid-line still resolves to that line, and offset 0 / pre-first-match resolves
 * to the first line), and the next line is the span after it. Empty spans →
 * [WristLines.EMPTY].
 */
fun wristLinesAt(spans: List<LineSpan>, positionChar: Int): WristLines {
    if (spans.isEmpty()) return WristLines.EMPTY
    var idx = 0
    for (k in spans.indices) {
        if (spans[k].start <= positionChar) idx = k else break
    }
    return WristLines(
        current = spans[idx].text,
        next = spans.getOrNull(idx + 1)?.text ?: "",
    )
}
