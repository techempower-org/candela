package `in`.jphe.storyvox.playback.transcribe

/**
 * Issue #1368 — strips the parts of a teleprompter script the reader does
 * **not** say aloud before the [ForcedAligner] matches against it: production
 * cues in `[brackets]` (e.g. `[POST: JINGLE]`) and `====` / `----` section
 * banner rules. The TechEmpower Show scripts (the real test content) are full
 * of these, and the web teleprompter already excludes them when counting
 * spoken words — this matches that behaviour for the voice-follow path.
 *
 * ### Why offsets must be mapped back to source
 * If the aligner matched against the *stripped* text, its `positionChar` would
 * be an offset into that stripped string — but the reader scrolls against the
 * **original, displayed** chapter text. Feeding a stripped-space offset into an
 * original-space scroll mapping would drift the scroll by the cumulative length
 * of every cue above the cursor. So [spokenText] returns both the cleaned
 * [SpokenText.text] (what the aligner tokenizes) **and** a per-character map
 * back to the source ([SpokenText.toSourceOffset]); [VoicePacedScrollController]
 * matches in stripped space and publishes source-space offsets.
 *
 * Pure and deterministic — no Android — so the stripping + mapping are
 * unit-testable.
 */
object ScriptCueFilter {

    /** `[production cue]` — `[^\]]` so a multi-line cue block is still removed. */
    private val INLINE_CUE = Regex("\\[[^\\]]*\\]")

    /** A whole-line banner rule of `=` or `-` (e.g. `========`, `--------`). */
    private val BANNER_LINE = Regex("(?m)^[=\\-]{3,}$")

    /**
     * Remove cues + banner rules from [source], returning the spoken text and a
     * map from each kept character back to its index in [source].
     */
    fun spokenText(source: String): SpokenText {
        if (source.isEmpty()) return SpokenText("", IntArray(0))

        val removed = BooleanArray(source.length)
        fun mark(regex: Regex) {
            for (m in regex.findAll(source)) {
                for (i in m.range) if (i in source.indices) removed[i] = true
            }
        }
        mark(INLINE_CUE)
        mark(BANNER_LINE)

        val sb = StringBuilder(source.length)
        val map = IntArray(source.length)
        var kept = 0
        for (i in source.indices) {
            if (!removed[i]) {
                sb.append(source[i])
                map[kept] = i
                kept++
            }
        }
        return SpokenText(sb.toString(), map.copyOf(kept))
    }
}

/**
 * Spoken-only text plus a map from its character offsets back to the original
 * source string. Produced by [ScriptCueFilter.spokenText].
 */
class SpokenText(
    /** The script with cues + banners removed — what [ForcedAligner] tokenizes. */
    val text: String,
    private val sourceOffsets: IntArray,
) {
    /**
     * Map a character [spokenOffset] in [text] back to the corresponding offset
     * in the original source. Clamped to a valid index; returns 0 for empty text
     * so a pre-match position (offset 0) maps to the start of the source.
     */
    fun toSourceOffset(spokenOffset: Int): Int {
        if (sourceOffsets.isEmpty()) return 0
        return sourceOffsets[spokenOffset.coerceIn(0, sourceOffsets.lastIndex)]
    }
}
