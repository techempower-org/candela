package `in`.jphe.storyvox.feature.reader

/**
 * Issue #1287 — practice / "pause-for-me" rehearsal mode.
 *
 * Splits chapter text into a contiguous run of [TextSegment]s classified as
 * [SegmentKind.Narration] or [SegmentKind.Dialogue], so practice mode can
 * let TTS read the narration and hand the dialogue to the user (pause →
 * "your turn" → tap to continue).
 *
 * Pure logic, no Compose/Android/engine deps, so the turn-taking boundaries
 * are unit-testable on the JVM (mirrors the [focusScrollTargetY] /
 * teleprompter pace-math pattern).
 *
 * Detection is deliberately **double-quote-only** — straight `"` (open/close
 * ambiguous, so it toggles) and curly `“…”` (unambiguous). This sidesteps
 * the apostrophe trap entirely: contractions and possessives (`don't`,
 * `it's`, `Hermione's`) use the single quote / apostrophe `'` `’`, which we
 * never treat as a delimiter — so they can't falsely flip "inside dialogue".
 *
 * Known limits (acceptable for an honor-system v1; see #1287):
 *  - Double quotes used as scare-quotes or inch-marks are misread as speech.
 *  - An unbalanced opening quote runs to end-of-text as dialogue.
 *  - Speaker attribution ([guessSpeaker]) is best-effort and may be null.
 */
enum class SegmentKind { Narration, Dialogue }

/**
 * A contiguous classified run of chapter text. [start] inclusive, [end]
 * exclusive — i.e. `chapterText.substring(start, end)` is the run, and the
 * segments tile the whole string with no gaps or overlaps.
 *
 * [speaker] is a best-effort guess of who voices a [SegmentKind.Dialogue]
 * run (null when unknown or for narration).
 */
data class TextSegment(
    val start: Int,
    val end: Int,
    val kind: SegmentKind,
    val speaker: String? = null,
)

private const val STRAIGHT_QUOTE = '"'
private const val CURLY_OPEN = '“' // “
private const val CURLY_CLOSE = '”' // ”

/**
 * Split [text] into narration/dialogue segments tiling the whole string.
 * Returns an empty list for empty input. Adjacent same-kind runs never
 * occur (narration runs are maximal; a dialogue run is one quote pair).
 *
 * When [attributeSpeakers] is true, each dialogue run gets a best-effort
 * [TextSegment.speaker] from the surrounding narration (see [guessSpeaker]).
 */
internal fun segmentDialogue(text: String, attributeSpeakers: Boolean = true): List<TextSegment> {
    if (text.isEmpty()) return emptyList()
    val out = mutableListOf<TextSegment>()
    var segStart = 0
    var inQuote = false
    var closer = STRAIGHT_QUOTE
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (!inQuote) {
            if (c == STRAIGHT_QUOTE || c == CURLY_OPEN) {
                // Close the narration run that precedes this opening quote.
                if (i > segStart) out += TextSegment(segStart, i, SegmentKind.Narration)
                segStart = i
                inQuote = true
                closer = if (c == CURLY_OPEN) CURLY_CLOSE else STRAIGHT_QUOTE
            }
        } else if (c == closer) {
            // Dialogue run is [segStart, i] inclusive of the closing quote.
            out += dialogueSegment(text, segStart, i + 1, attributeSpeakers)
            segStart = i + 1
            inQuote = false
        }
        i++
    }
    if (segStart < text.length) {
        out += if (inQuote) {
            dialogueSegment(text, segStart, text.length, attributeSpeakers)
        } else {
            TextSegment(segStart, text.length, SegmentKind.Narration)
        }
    }
    return out
}

private fun dialogueSegment(text: String, start: Int, end: Int, attribute: Boolean): TextSegment =
    TextSegment(
        start = start,
        end = end,
        kind = SegmentKind.Dialogue,
        speaker = if (attribute) guessSpeaker(text, start, end) else null,
    )

/** Speech verbs that flag an attribution clause (`"…," said Harry` / `Harry asked, "…"`). */
private val SPEECH_VERBS = listOf(
    "said", "says", "asked", "asks", "replied", "replies", "whispered", "shouted",
    "cried", "muttered", "exclaimed", "answered", "added", "continued", "called",
    "murmured", "yelled", "began", "responded", "remarked", "declared", "snapped",
).joinToString("|")

/** Pronouns / articles that look capitalised at sentence start but aren't names. */
private val NON_NAMES = setOf(
    "He", "She", "They", "It", "I", "We", "You", "The", "A", "An", "And", "But", "Then",
)

// `Harry said` / `Professor McGonagall asked` (capitalised name immediately before a speech verb)
private val SPEAKER_BEFORE_VERB = Regex("\\b([A-Z][a-zA-Z]+)\\s+(?:$SPEECH_VERBS)\\b")
// `said Harry` / `asked Hermione` (speech verb immediately before a capitalised name)
private val VERB_BEFORE_SPEAKER = Regex("\\b(?:$SPEECH_VERBS)\\s+([A-Z][a-zA-Z]+)\\b")

/**
 * Best-effort: who voices the dialogue run [dialogueStart, dialogueEnd)?
 * Looks first at the narration immediately AFTER the closing quote
 * (`"…," she said` is the common attribution position), then BEFORE the
 * opening quote (`Harry said, "…"`). Returns null when no confident match —
 * practice mode then just shows a generic "Your turn".
 */
internal fun guessSpeaker(text: String, dialogueStart: Int, dialogueEnd: Int): String? {
    val after = text.substring(dialogueEnd, (dialogueEnd + ATTR_WINDOW).coerceAtMost(text.length))
    speakerIn(after)?.let { return it }
    val before = text.substring((dialogueStart - ATTR_WINDOW).coerceAtLeast(0), dialogueStart)
    return speakerIn(before)
}

private const val ATTR_WINDOW = 48

private fun speakerIn(window: String): String? {
    // "said Harry" reads more reliably than the before-verb form, so check it first.
    VERB_BEFORE_SPEAKER.find(window)?.groupValues?.get(1)?.let { if (it !in NON_NAMES) return it }
    SPEAKER_BEFORE_VERB.find(window)?.groupValues?.get(1)?.let { if (it !in NON_NAMES) return it }
    return null
}

// ─────────────────────────────────────────────────────────────────────
// Turn-taking decision logic (pure) — drives the auto-pause in practice mode
// ─────────────────────────────────────────────────────────────────────
// Practice mode plays narration via TTS and watches the playhead's current
// char offset (UiPlaybackState.sentenceStart). These helpers answer the two
// questions the turn-taking effect asks each time the playhead moves; both
// are pure so the hand-off boundaries are unit-testable.

/**
 * The dialogue segment the playhead at [charOffset] currently falls in, or
 * null when the playhead is in narration. When non-null, it's the user's
 * turn: practice mode pauses TTS and shows "your turn" (+ [TextSegment.speaker]
 * if known).
 */
internal fun dialogueAt(segments: List<TextSegment>, charOffset: Int): TextSegment? =
    segments.firstOrNull { it.kind == SegmentKind.Dialogue && charOffset in it.start until it.end }

/**
 * The next dialogue segment that starts at or after [charOffset] — i.e. the
 * upcoming hand-off point while narration is playing. Null when no dialogue
 * remains (TTS just narrates to the end). Used to know where the next pause
 * will land; segments are in document order so the first match wins.
 */
internal fun nextDialogueAtOrAfter(segments: List<TextSegment>, charOffset: Int): TextSegment? =
    segments.firstOrNull { it.kind == SegmentKind.Dialogue && it.start >= charOffset }

/**
 * Where TTS should resume after the user reads [dialogue] aloud: the end of
 * the dialogue run, which is the start of the following narration (or the
 * chapter end). practice mode seeks here on "continue", so the synthesized
 * voice never reads the line the user just voiced.
 */
internal fun resumeOffsetAfter(dialogue: TextSegment): Int = dialogue.end
