package `in`.jphe.storyvox.data.script

/**
 * Issue #1367 — teleprompter script model + parser, ported from the TechEmpower
 * Show web teleprompter (`techempower/show/ep2/teleprompter.html`).
 *
 * Issue #1369 follow-up — moved from `:feature` into `:core-data` so it is the
 * **single** canonical teleprompter-script parser: the recording overlay
 * (`TeleprompterOverlay`) renders from [ParsedScript], and the persisted
 * [`in`.jphe.storyvox.data.db.entity.TeleprompterScript] computes its
 * spoken-word duration estimate from the same [spokenWordCount] — no second,
 * drifting word-count implementation.
 *
 * Production scripts (multi-speaker, section-structured, with production cues)
 * follow a plain-text convention the web prompter already parses:
 *  - **Section banners** — a title line framed by rows of `=` (or `-`):
 *    ```
 *    ================================
 *    BENEFIT ONE: THE ZERO-DOLLAR PHONE
 *    ================================
 *    ```
 *  - **Speaker tags** — an ALL-CAPS name + colon starting a block (`SHAWNA:`),
 *    which colours that turn and every following turn until the next tag.
 *  - **Production cues** — `[POST: ...]` blocks (or inline `[...]`), shown
 *    dimmed/italic and **never counted as spoken words** (they're editor notes,
 *    not dialogue).
 *  - Everything else is spoken dialogue.
 *
 * The parser is pure (no Android / Compose) so it's unit-testable against the
 * real ep1/ep2 scripts. Plain prose with none of these markers degrades to a
 * single unattributed [ScriptBlock.Line] — so a novel chapter still renders.
 *
 * ## Classification order matters
 * Section banners are detected **before** speaker tags: `BENEFIT ONE:` /
 * `THE MYTH:` are ALL-CAPS-and-colon (they look like speaker tags) but always
 * sit between `=` rows, so catching banners first keeps them out of the speaker
 * path — exactly as the reference JS does.
 */

/** A parsed block of the script in render order. */
sealed interface ScriptBlock {
    /** A centered section banner (the title between `=` rows). */
    data class Section(val title: String) : ScriptBlock

    /** A standalone production cue (`[POST: ...]`) — not spoken. */
    data class Cue(val text: String) : ScriptBlock

    /**
     * A spoken line. [speaker] is the attributed speaker (null before the first
     * tag / in unattributed prose); [showLabel] is true only when this block
     * carried an explicit `NAME:` tag, so multi-block turns repeat the colour
     * bar but not the label. [segments] interleaves spoken text and inline cues.
     */
    data class Line(
        val speaker: ScriptSpeaker?,
        val showLabel: Boolean,
        val segments: List<LineSegment>,
    ) : ScriptBlock
}

/** A speaker, with a stable [colorIndex] assigned by order of first appearance
 *  (0 = first speaker seen). The UI maps the index onto a colour palette, so
 *  the first speaker is coral, the second teal, etc. — matching the web
 *  prompter's Shawna/Jeff colouring without hard-coding names. */
data class ScriptSpeaker(val name: String, val colorIndex: Int)

/** A run within a spoken line. */
sealed interface LineSegment {
    data class Spoken(val text: String) : LineSegment
    data class InlineCue(val text: String) : LineSegment
}

/**
 * The parse result: [blocks] in render order plus [spokenWordCount] (cues and
 * section banners excluded), which drives the auto-scroll pace so the prompter
 * finishes a script in roughly `spokenWords / wpm` minutes — the cues scroll by
 * "for free" in the gaps.
 */
data class ParsedScript(
    val blocks: List<ScriptBlock>,
    val spokenWordCount: Int,
)

private val BLANK_LINE = Regex("\\n\\s*\\n")
private val DIVIDER = Regex("^[=\\-]{3,}$")
private val STANDALONE_CUE = Regex("^\\[[^\\]]+\\]$")
private val INLINE_CUE = Regex("\\[[^\\]]+\\]")
private val WHITESPACE = Regex("\\s+")

/** A speaker tag: an ALL-CAPS (digits / space / `.` / `'` / `-` allowed) name
 *  up to 25 chars, then a colon, then the rest of the (joined) block. The
 *  upper-case-only, length-capped shape keeps lowercase prose such as `Hosts:`
 *  or `PROMPTER NOTES (do not read):` from registering as speakers. */
private val SPEAKER_TAG = Regex("^([A-Z][A-Z0-9 .'\\-]{0,24}):\\s*(.*)$")

/** Parse a raw chapter/script into [ParsedScript]. See the file KDoc for the
 *  convention. Safe on arbitrary text — unmarked prose becomes plain lines. */
fun parseTeleprompterScript(raw: String): ParsedScript {
    val blocks = mutableListOf<ScriptBlock>()
    val speakerIndex = LinkedHashMap<String, Int>()
    var currentSpeaker: ScriptSpeaker? = null
    var spokenWords = 0

    val rawBlocks = raw.split(BLANK_LINE).map { it.trim() }.filter { it.isNotEmpty() }
    for (block in rawBlocks) {
        val lines = block.split("\n").map { it.trim() }

        // 1 — section banner (checked first; see file KDoc).
        if (lines.any { DIVIDER.matches(it) }) {
            val title = lines.filterNot { DIVIDER.matches(it) }.joinToString(" ").trim()
            if (title.isNotEmpty()) {
                blocks.add(ScriptBlock.Section(title))
                currentSpeaker = null
            }
            continue
        }

        val text = lines.joinToString(" ").trim()
        if (text.isEmpty()) continue

        // 2 — standalone production cue.
        if (STANDALONE_CUE.matches(text)) {
            blocks.add(ScriptBlock.Cue(text))
            continue
        }

        // 3 — speaker tag (optional), then the spoken line.
        var lineText = text
        var explicitTag = false
        val match = SPEAKER_TAG.matchEntire(text)
        if (match != null) {
            val name = match.groupValues[1].trim()
            val index = speakerIndex.getOrPut(name) { speakerIndex.size }
            currentSpeaker = ScriptSpeaker(name, index)
            lineText = match.groupValues[2].trim()
            explicitTag = true
        }

        if (lineText.isEmpty()) {
            // A bare `NAME:` block — render the label, dialogue follows in the
            // next block (inherits currentSpeaker).
            blocks.add(ScriptBlock.Line(currentSpeaker, showLabel = true, segments = emptyList()))
            continue
        }

        val segments = segmentLine(lineText)
        spokenWords += segments.filterIsInstance<LineSegment.Spoken>()
            .sumOf { countWords(it.text) }
        blocks.add(ScriptBlock.Line(currentSpeaker, showLabel = explicitTag, segments = segments))
    }

    return ParsedScript(blocks, spokenWords)
}

/** Split a line into spoken runs and inline `[...]` cues, in order. */
private fun segmentLine(text: String): List<LineSegment> {
    val segments = mutableListOf<LineSegment>()
    var cursor = 0
    for (match in INLINE_CUE.findAll(text)) {
        if (match.range.first > cursor) {
            val spoken = text.substring(cursor, match.range.first)
            if (spoken.isNotEmpty()) segments.add(LineSegment.Spoken(spoken))
        }
        segments.add(LineSegment.InlineCue(match.value))
        cursor = match.range.last + 1
    }
    if (cursor < text.length) {
        val spoken = text.substring(cursor)
        if (spoken.isNotEmpty()) segments.add(LineSegment.Spoken(spoken))
    }
    if (segments.isEmpty()) segments.add(LineSegment.Spoken(text))
    return segments
}

private fun countWords(text: String): Int =
    if (text.isBlank()) 0 else text.trim().split(WHITESPACE).count { it.isNotBlank() }
