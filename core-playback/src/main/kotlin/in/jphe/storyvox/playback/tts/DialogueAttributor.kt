package `in`.jphe.storyvox.playback.tts

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1283 — one attributed run of dialogue. [characterName] speaks the
 * quoted text at `text.substring(startOffset, endOffset)` (offsets bracket
 * the quotation marks). Offsets index the original chapter text so they
 * line up with [Sentence] `(startChar, endChar)` ranges.
 */
data class DialogueSegment(
    val characterName: String,
    val startOffset: Int,
    val endOffset: Int,
)

/**
 * Heuristic dialogue attribution (#1283 MVP, Phase 1). Pairs double-quote
 * spans (straight `"..."` and curly `“...”`) and attributes each to a
 * speaker by matching a dialogue tag — `said Alice` / `Alice said` —
 * immediately after the quote (preferred) or before it. Pure,
 * dependency-free, on-device: no network, no NLP model, no Android types.
 *
 * Deliberately conservative — when no clear proper-noun name sits next to a
 * quote, the span is left **unattributed** (omitted from the result) so the
 * caller falls back to the narrator voice rather than guessing. Single
 * quotes are NOT treated as span delimiters, so a nested `‘…’` stays inside
 * its outer span. Pronoun/coreference resolution ("he said") and
 * LLM-assisted attribution are explicit Phase-2 scope on #1283 — see the
 * research comment on the issue.
 */
@Singleton
class DialogueAttributor @Inject constructor() {

    /** Speaker spans in document order, non-overlapping (one per quote). */
    fun attribute(text: String): List<DialogueSegment> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<DialogueSegment>()
        for ((start, end) in quoteSpans(text)) {
            // After the quote wins ("…," said Alice); fall back to before
            // the quote (Bob said, "…").
            val name = speakerAfter(text, end) ?: speakerBefore(text, start)
            if (name != null) out += DialogueSegment(name, start, end)
        }
        return out
    }

    /** `(startInclusive, endExclusive)` of each double-quoted span, both
     *  styles, sorted by start. Inner single quotes are not delimiters. */
    private fun quoteSpans(text: String): List<Pair<Int, Int>> {
        val spans = mutableListOf<Pair<Int, Int>>()
        CURLY.findAll(text).forEach { spans += it.range.first to (it.range.last + 1) }
        STRAIGHT.findAll(text).forEach { spans += it.range.first to (it.range.last + 1) }
        return spans.sortedBy { it.first }
    }

    /** Name from a dialogue tag right after the quote: `said Alice` or
     *  `Alice replied`. */
    private fun speakerAfter(text: String, spanEnd: Int): String? {
        if (spanEnd >= text.length) return null
        val window = text.substring(spanEnd, minOf(text.length, spanEnd + WINDOW))
            .substringBefore('\n')
        val verbFirst = AFTER_VERB_NAME.find(window)?.groupValues?.get(1)
        val nameFirst = AFTER_NAME_VERB.find(window)?.groupValues?.get(1)
        return (verbFirst ?: nameFirst)?.takeUnless { it in STOPWORDS }
    }

    /** Name from a dialogue tag right before the quote: `Bob said, "…"`. */
    private fun speakerBefore(text: String, spanStart: Int): String? {
        if (spanStart <= 0) return null
        val window = text.substring(maxOf(0, spanStart - WINDOW), spanStart)
            .substringAfterLast('\n')
        return BEFORE_NAME_VERB.find(window)?.groupValues?.get(1)?.takeUnless { it in STOPWORDS }
    }

    private companion object {
        /** Chars to scan either side of a quote for a dialogue tag. */
        const val WINDOW = 48

        /** Speech verbs that mark a dialogue tag. */
        private const val VERBS =
            "said|asked|replied|answered|shouted|whispered|murmured|muttered|" +
                "cried|exclaimed|added|continued|began|called|demanded|repeated|" +
                "wondered|breathed|growled|snapped|sighed|yelled|hissed|gasped|roared"

        /** A capitalized proper-noun-ish token (name capture is group 1). */
        private const val NAME = "([A-Z][\\p{L}]+)"

        /** Pronouns / articles / sentence-openers that look like names but
         *  aren't — reject so we don't attribute to "He" or "The". */
        val STOPWORDS = setOf(
            "He", "She", "They", "It", "We", "You", "I", "His", "Her", "Their",
            "The", "A", "An", "And", "But", "Then", "There", "That", "This",
            "What", "Who", "Why", "How", "When", "Where", "So", "Yet", "Still",
        )

        // Anchored at the window start (just past the quote): optional
        // leading whitespace/comma/dash, then either verb→name or name→verb.
        val AFTER_VERB_NAME = Regex("^[\\s,“\"”—-]*\\b(?:$VERBS)\\b\\s+$NAME")
        val AFTER_NAME_VERB = Regex("^[\\s,“\"”—-]*$NAME\\s+\\b(?:$VERBS)\\b")
        // Anchored at the window end (just before the quote): name→verb then
        // optional trailing comma/colon/dash/space.
        val BEFORE_NAME_VERB = Regex("$NAME\\s+\\b(?:$VERBS)\\b[\\s,:—-]*$")

        val CURLY = Regex("“[^“”]*”")
        val STRAIGHT = Regex("\"[^\"]*\"")
    }
}
