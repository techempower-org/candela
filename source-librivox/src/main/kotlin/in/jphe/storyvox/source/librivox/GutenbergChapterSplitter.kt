package `in`.jphe.storyvox.source.librivox

/**
 * Issue #1224 — split a LibriVox book's companion Project Gutenberg
 * plain text into per-audio-section segments for read-along.
 *
 * LibriVox serves a book as N per-section MP3s; Project Gutenberg serves
 * the matching public-domain text as one blob. To show the right text
 * while a given section's human narration plays, the blob has to be cut
 * into N pieces aligned to the section boundaries.
 *
 * Two strategies, in order:
 *
 *  1. **Heading-aligned** — when the number of detected chapter headings
 *     ("CHAPTER I", "Chapter 1", a lone Roman numeral, …) is *exactly*
 *     [sectionCount], cut at those headings. Any front matter before the
 *     first heading rides along with segment 0. This is the
 *     high-confidence path: for the typical classic novel one Gutenberg
 *     chapter maps to one LibriVox section, so the mapping is clean.
 *
 *  2. **Proportional** — otherwise, walk paragraphs and balance them into
 *     [sectionCount] roughly word-equal segments on paragraph boundaries
 *     (char-split as a last resort when there are fewer paragraphs than
 *     sections). Alignment is approximate, but read-along is a manual
 *     "follow along" affordance — there is no word-level sync until STT
 *     (#1223) — so approximate text beats the status-quo single bonus
 *     chapter that showed nothing during section playback.
 *
 * The exact-count gate is deliberate: heading detection is noisy (tables
 * of contents, prefaces, dedications), so anything other than a clean
 * 1:1 match falls back to the always-correct proportional split rather
 * than risk an off-by-one misalignment that is worse than no headings.
 *
 * Pure + deterministic: no Android, no network, fully unit-testable.
 */
internal object GutenbergChapterSplitter {

    /**
     * Cut [plainText] into exactly [sectionCount] read-along segments
     * (one per LibriVox audio section). Returns an empty list only when
     * [sectionCount] <= 0; otherwise the result size is always
     * [sectionCount] (trailing segments may be empty for pathological
     * input, never more entries than sections).
     */
    fun split(plainText: String, sectionCount: Int): List<String> {
        if (sectionCount <= 0) return emptyList()
        val text = plainText.trim()
        if (text.isEmpty()) return List(sectionCount) { "" }
        if (sectionCount == 1) return listOf(text)

        val headings = detectBodyHeadings(text)
        return if (headings.size == sectionCount) {
            // Segment 0 absorbs any front matter before the first heading,
            // so the cut points are the 2nd..Nth heading offsets — that's
            // (sectionCount - 1) cuts producing sectionCount segments.
            sliceAt(text, headings.drop(1))
        } else {
            proportionalSplit(text, sectionCount)
        }
    }

    // ─── heading detection ──────────────────────────────────────────────

    /**
     * Char offsets of lines that look like real chapter headings. A
     * "real" heading is followed by at least [MIN_BODY_GAP] characters of
     * text before the next heading; this drops table-of-contents lines,
     * which are packed tightly together with no body between them.
     */
    private fun detectBodyHeadings(text: String): List<Int> {
        val offsets = ArrayList<Int>()
        var pos = 0
        for (line in text.split('\n')) {
            if (isHeadingLine(line.trim())) offsets.add(pos)
            pos += line.length + 1 // + the '\n' that split() consumed
        }
        if (offsets.isEmpty()) return emptyList()
        return offsets.filterIndexed { i, off ->
            val next = offsets.getOrNull(i + 1) ?: text.length
            next - off >= MIN_BODY_GAP
        }
    }

    private fun isHeadingLine(line: String): Boolean =
        line.isNotEmpty() && (
            HEADING_KEYWORD.matches(line) ||
                HEADING_ROMAN.matches(line) ||
                HEADING_NUMBER.matches(line)
            )

    // ─── slicing ────────────────────────────────────────────────────────

    /** Cut [text] at each ascending offset in [cutPoints]; yields
     *  cutPoints.size + 1 trimmed segments. */
    private fun sliceAt(text: String, cutPoints: List<Int>): List<String> {
        val bounds = buildList {
            add(0)
            addAll(cutPoints)
            add(text.length)
        }
        return bounds.zipWithNext { a, b -> text.substring(a, b).trim() }
    }

    // ─── proportional fallback ──────────────────────────────────────────

    private fun proportionalSplit(text: String, n: Int): List<String> {
        val paras = text.split(PARAGRAPH_BREAK)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (paras.size < n) return charSplit(text, n)

        val totalWords = paras.sumOf { wordCount(it) }.coerceAtLeast(1)
        val target = totalWords.toDouble() / n
        val result = ArrayList<String>(n)
        val cur = StringBuilder()
        var curWords = 0
        for ((i, p) in paras.withIndex()) {
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(p)
            curWords += wordCount(p)
            val remainingParas = paras.size - i - 1
            val remainingSegs = n - result.size - 1
            // Force a close when there are only just enough paragraphs left
            // to give every remaining segment at least one — guarantees we
            // never starve the tail. Otherwise close once we've reached the
            // per-segment word target.
            val mustClose = remainingSegs > 0 && remainingParas <= remainingSegs
            val wantClose = result.size < n - 1 && curWords >= target
            if (mustClose || wantClose) {
                result.add(cur.toString())
                cur.setLength(0)
                curWords = 0
            }
        }
        if (cur.isNotEmpty()) result.add(cur.toString())
        while (result.size < n) result.add("")
        if (result.size > n) {
            val head = result.subList(0, n - 1).toList()
            val tail = result.subList(n - 1, result.size).joinToString("\n\n")
            return head + tail
        }
        return result
    }

    /** Even split by character count, breaking on whitespace so words
     *  aren't cut. Last resort when a book has fewer paragraphs than
     *  sections (e.g. one giant un-delimited blob). */
    private fun charSplit(text: String, n: Int): List<String> {
        if (text.isEmpty()) return List(n) { "" }
        val approx = (text.length + n - 1) / n
        val result = ArrayList<String>(n)
        var start = 0
        for (i in 0 until n) {
            if (start >= text.length) {
                result.add("")
                continue
            }
            if (i == n - 1) {
                result.add(text.substring(start).trim())
                start = text.length
                continue
            }
            var end = (start + approx).coerceAtMost(text.length)
            while (end < text.length && !text[end].isWhitespace()) end++
            result.add(text.substring(start, end).trim())
            start = end
        }
        while (result.size < n) result.add("")
        return result
    }

    private fun wordCount(s: String): Int {
        val t = s.trim()
        return if (t.isEmpty()) 0 else t.split(WHITESPACE).size
    }

    // ─── patterns ───────────────────────────────────────────────────────

    /** Blank-line paragraph delimiter in Gutenberg plain text. */
    private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")
    private val WHITESPACE = Regex("\\s+")

    /**
     * Real chapter bodies run for thousands of characters; table-of-
     * contents entries sit a line apart. 400 chars cleanly separates the
     * two without tripping on genuinely short chapters in the common case
     * (and a miss only downgrades to the proportional split).
     */
    private const val MIN_BODY_GAP = 400

    /** "CHAPTER I", "Chapter 1", "BOOK IV — The Return", etc. The keyword
     *  must be followed by a Roman numeral or digits; trailing title text
     *  is allowed. */
    private val HEADING_KEYWORD = Regex(
        "^(?:CHAPTER|BOOK|PART|SECTION|LETTER|ACT|SCENE|CANTO)\\s+(?:[IVXLCDM]+|\\d+)\\b.*$",
        RegexOption.IGNORE_CASE,
    )

    /** A lone upper-case Roman numeral line ("IV", "XII."). Case-sensitive
     *  so it doesn't match lower-case words like "i" or "mix". */
    private val HEADING_ROMAN = Regex("^[IVXLCDM]{1,7}\\.?$")

    /** A lone number line ("12", "12."). */
    private val HEADING_NUMBER = Regex("^\\d{1,3}\\.?$")
}
