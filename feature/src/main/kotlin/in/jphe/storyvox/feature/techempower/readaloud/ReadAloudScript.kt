package `in`.jphe.storyvox.feature.techempower.readaloud

import `in`.jphe.storyvox.feature.api.UiSpeakOutcome

/**
 * Issue #1580 — pure text assembly for the benefits read-aloud control.
 *
 * Every TechEMPOWER benefits surface (screener results, letter decoder, call
 * cards, deadline keeper, fillable PDF, doc wallet, household profile) builds
 * the script it hands to [ReadAloudControl] from already-localized pieces
 * (`stringResource` output + the user's own text), so the spoken language
 * always follows the active app language (epic #1520 invariant 2). This
 * object only normalizes and joins those pieces; it never adds user-facing
 * words of its own, so it stays language-neutral and plain-JVM testable.
 */
object ReadAloudScript {

    private val WHITESPACE = Regex("\\s+")

    /** Characters that already end a spoken phrase — no period appended. */
    private const val TERMINATORS = ".!?…:;。"

    /**
     * Join [parts] into one utterance: each part is trimmed and its internal
     * whitespace (OCR line breaks, double spaces) collapsed; blank / null parts
     * are dropped; a part that doesn't already end in sentence punctuation gets
     * a period, so the TTS sentence chunker pauses between them instead of
     * running a label straight into the next value.
     */
    fun build(parts: List<String?>): String =
        parts.asSequence()
            .mapNotNull { normalize(it) }
            .map { if (it.last() in TERMINATORS) it else "$it." }
            .joinToString(" ")

    fun build(vararg parts: String?): String = build(parts.asList())

    /**
     * "Label: value" for a form-style field. When [value] is blank, returns
     * "Label: [emptyValue]" if an empty-value phrase is supplied (so a listener
     * hears which fields are still missing), else null (the field is skipped).
     */
    fun labeled(label: String, value: String?, emptyValue: String? = null): String? {
        val l = normalize(label) ?: return null
        val v = normalize(value) ?: normalize(emptyValue) ?: return null
        val sep = if (l.last() in TERMINATORS) " " else ": "
        return "$l$sep$v"
    }

    private fun normalize(s: String?): String? =
        s?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * Issue #1580 — what a tap on a read-aloud control should do. Pure so the
 * toggle rules are unit-tested without a ViewModel or the playback seam.
 */
sealed interface ReadAloudAction {
    /** Stop the utterance this control started. */
    data object Stop : ReadAloudAction

    /** Start speaking [text] (replacing any other control's utterance). */
    data class Speak(val text: String) : ReadAloudAction

    /** Nothing to read (blank script) — ignore the tap. */
    data object None : ReadAloudAction
}

object ReadAloudSession {

    /**
     * True when the control identified by [key] owns the current utterance:
     * it was the last control tapped ([activeKey]) and speech is in flight
     * ([busy] — either still warming the voice or audibly speaking).
     */
    fun isActive(key: String, activeKey: String?, busy: Boolean): Boolean =
        busy && activeKey == key

    /**
     * Toggle rule: tapping the control that is currently speaking stops it;
     * tapping any other control (or this one while idle) starts its own
     * script — one utterance at a time, the newest tap wins.
     */
    fun decide(key: String, activeKey: String?, busy: Boolean, text: String): ReadAloudAction = when {
        isActive(key, activeKey, busy) -> ReadAloudAction.Stop
        text.isBlank() -> ReadAloudAction.None
        else -> ReadAloudAction.Speak(text)
    }

    /**
     * Issue #1776 — which failure (if any) to show for a finished speak
     * request. Started and Cancelled (superseded / stopped / blank) show
     * nothing; the two real failures get their own message so the user knows
     * whether to go pick a voice or just try again.
     */
    fun failureFor(outcome: UiSpeakOutcome): ReadAloudFailure? = when (outcome) {
        UiSpeakOutcome.Started, UiSpeakOutcome.Cancelled -> null
        UiSpeakOutcome.NoVoice -> ReadAloudFailure.NoVoice
        UiSpeakOutcome.Unavailable -> ReadAloudFailure.EngineUnavailable
    }
}

/** Issue #1776 — why a read-aloud tap produced no speech. */
enum class ReadAloudFailure {
    /** No voice is installed / picked — point the user at Voices. */
    NoVoice,

    /** The playback engine couldn't start in time — a retry usually works. */
    EngineUnavailable,
}
