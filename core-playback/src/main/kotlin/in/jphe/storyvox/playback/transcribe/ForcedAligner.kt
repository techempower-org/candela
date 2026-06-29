package `in`.jphe.storyvox.playback.transcribe

/**
 * Issue #1291 — aligns a live stream of speech-recognized words against the
 * known chapter text and reports where in the text the speaker currently is
 * (a character offset). The voice-paced teleprompter (#1291) uses that
 * offset to drive the scroll; the read-along reader could use it too.
 *
 * The recognizer is noisy — it emits partial words, misrecognitions,
 * occasionally skips words, and pauses — so we never trust a single token.
 * Instead we keep a **cursor** into the chapter's word tokens and, for each
 * recognized word, fuzzy-match it against a **sliding window** around the
 * cursor (a few tokens behind, several ahead) using normalized edit
 * distance plus prefix matching (for partial words). The best in-threshold
 * match advances the cursor; a miss holds position; a run of misses
 * triggers a wider **re-sync** search so we recover after drift (e.g. the
 * speaker jumps ahead, or the recognizer garbles a stretch).
 *
 * Pure and deterministic — no Android, no I/O — so the whole matching
 * policy is unit-testable by feeding word sequences and asserting the
 * reported offset. The recognizer/mic plumbing lives elsewhere
 * ([MicCaptureProcessor]); this class only sees `String` tokens.
 */
class ForcedAligner(
    chapterText: String,
    /** Tokens ahead of the cursor to consider — covers skipped words.
     *  ~30 per Lucid's #1291 forced-alignment research (20–40-word window,
     *  matching the hotword-bias span of the upcoming reference text). */
    private val windowAhead: Int = 30,
    /** Tokens behind the cursor — tolerates a re-spoken word / short re-read. */
    private val windowBehind: Int = 4,
    /** Max edit-distance / max-length for a word to count as a match. */
    private val maxNormalizedDistance: Float = 0.34f,
    /** Consecutive misses before a wider re-sync sweep kicks in. */
    private val resyncAfterMisses: Int = 4,
    /** Recognizer confidence below which a word is held, not matched (#1291,
     *  Lucid finding #4): coughs / "umm"s / asides arrive low-confidence and
     *  should freeze the scroll rather than advance or count as drift.
     *  sherpa-onnx exposes per-token vocab log-probs to feed this. */
    private val minWordConfidence: Float = 0.5f,
) {
    private val tokens: List<TextToken> = tokenizeWords(chapterText)

    /** Index of the last matched token, or -1 before the first match. */
    private var cursor: Int = -1
    private var consecutiveMisses: Int = 0

    /** Character offset of where the speaker currently is in [chapterText]
     *  — the start of the last matched word (0 before any match / empty text). */
    val positionChar: Int
        get() = tokens.getOrNull(cursor)?.startChar ?: 0

    /** Index of the last matched token (-1 before the first match). Exposed
     *  for the scroller / tests to reason about progress. */
    val matchedTokenIndex: Int get() = cursor

    val tokenCount: Int get() = tokens.size

    /**
     * Feed one recognized word. Advances the cursor to the best in-window
     * match (or re-syncs / holds), and returns the resulting [positionChar].
     */
    fun onWord(recognized: String, confidence: Float = 1f): Int {
        if (tokens.isEmpty()) return 0
        // #1291 (Lucid finding #4) — hold on a low-confidence token: a cough /
        // "umm" / off-script aside should freeze the scroll, not advance it or
        // count toward the drift that triggers a re-sync.
        if (confidence < minWordConfidence) return positionChar
        val w = normalize(recognized)
        if (w.length < MIN_TOKEN_LEN) return positionChar // ignore noise / "a", "I"

        val from = (cursor - windowBehind).coerceAtLeast(0)
        val to = (cursor + windowAhead).coerceAtMost(tokens.lastIndex)
        val best = bestMatch(w, from, to, maxNormalizedDistance)
        if (best >= 0) {
            cursor = best
            consecutiveMisses = 0
            return positionChar
        }

        // Miss. After a run of them, sweep wider (stricter threshold so we
        // don't false-anchor on a common short word) to recover from drift.
        consecutiveMisses++
        if (consecutiveMisses >= resyncAfterMisses) {
            val sweepFrom = (cursor + 1).coerceAtLeast(0)
            val resync = bestMatch(w, sweepFrom, tokens.lastIndex, maxNormalizedDistance * 0.6f)
            if (resync >= 0) {
                cursor = resync
                consecutiveMisses = 0
            }
        }
        return positionChar
    }

    /** Reset to the start (e.g. user restarts the chapter). */
    fun reset() {
        cursor = -1
        consecutiveMisses = 0
    }

    /**
     * Best-matching token index in [from]..[to] for normalized word [w], or
     * -1 if none is within [threshold]. A prefix relationship (one string
     * starts with the other, both ≥ [MIN_TOKEN_LEN]) counts as a perfect
     * match so partial/cut-off recognizer words still land. Ties go to the
     * earliest token (forward progress).
     */
    private fun bestMatch(w: String, from: Int, to: Int, threshold: Float): Int {
        var bestIdx = -1
        var bestCost = Float.MAX_VALUE
        for (i in from..to) {
            val t = tokens[i].norm
            val cost = if (isPrefixMatch(w, t)) 0f else {
                editDistance(w, t).toFloat() / maxOf(w.length, t.length)
            }
            if (cost < bestCost) {
                bestCost = cost
                bestIdx = i
                if (cost == 0f) break // can't beat an exact/prefix hit
            }
        }
        return if (bestCost <= threshold) bestIdx else -1
    }

    private fun isPrefixMatch(a: String, b: String): Boolean {
        if (a.length < MIN_TOKEN_LEN || b.length < MIN_TOKEN_LEN) return false
        return a.startsWith(b) || b.startsWith(a)
    }

    companion object {
        /** Below this length a word is too common/ambiguous to anchor on. */
        const val MIN_TOKEN_LEN: Int = 2
    }
}

/** One word of the chapter text with its span in the original string. */
data class TextToken(val norm: String, val startChar: Int, val endChar: Int)

/**
 * Split [text] into word tokens, preserving each word's character span in
 * the original string. Words are letter/digit runs (apostrophes kept so
 * contractions stay whole); [TextToken.norm] is lower-cased with
 * apostrophes stripped so `"Don't"` and recognizer output `"dont"` match.
 */
fun tokenizeWords(text: String): List<TextToken> {
    val out = ArrayList<TextToken>()
    val regex = Regex("[\\p{L}\\p{N}']+")
    for (m in regex.findAll(text)) {
        val raw = m.value
        val norm = raw.replace("'", "").lowercase()
        if (norm.isEmpty()) continue
        out.add(TextToken(norm = norm, startChar = m.range.first, endChar = m.range.last + 1))
    }
    return out
}

private fun normalize(word: String): String =
    word.trim().replace("'", "").filter { it.isLetterOrDigit() }.lowercase()

/**
 * Levenshtein edit distance (two-row DP). Used to score how close a
 * recognized word is to a candidate text token.
 */
fun editDistance(a: String, b: String): Int {
    if (a == b) return 0
    if (a.isEmpty()) return b.length
    if (b.isEmpty()) return a.length
    var prev = IntArray(b.length + 1) { it }
    var curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost)
        }
        val tmp = prev; prev = curr; curr = tmp
    }
    return prev[b.length]
}
