package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Issue #1369 — a user-authored teleprompter script.
 *
 * The teleprompter (solo-rehearsal / read-aloud mode, #1239) historically
 * scrolled only the *current chapter's* body. This entity lets a user save,
 * edit, and organize standalone scripts — talks, narration drafts, lines to
 * rehearse — that have no backing [Fiction]/[Chapter], then load any of them
 * into the teleprompter on demand.
 *
 * ## Identity
 * [id] is a client-generated UUID (see `SyncIds` for the same pattern used by
 * [Annotation]). A stable id is what lets a list-screen swipe-to-delete with
 * undo address one row, and what a future cross-device syncer would key on.
 *
 * ## No foreign keys
 * Scripts are independent objects — they are deliberately NOT tied to a
 * fiction or chapter, so nothing cascades into or out of them. Removing a book
 * never touches a script; deleting a script never touches a book.
 *
 * ## Duration estimate
 * [estimatedDurationSecs] is a *stored snapshot* of "how long this reads aloud"
 * at the canonical [WPM_BASELINE] pace, recomputed on every save via
 * [estimateDurationSecs]. It is denormalized into the row so the list screen
 * can render a duration badge without re-tokenizing every body on every frame.
 * The edit screen shows a *live* duration at the user's current teleprompter
 * WPM (which may differ from the baseline); the two are intentionally distinct
 * — the row badge is a stable at-a-glance figure, the editor figure tracks the
 * pace the user will actually read at.
 *
 * ## Tags
 * [tags] is a comma-separated string rather than a junction table — scripts are
 * a lightweight, single-user, local-first feature and the search query
 * (`tags LIKE '%query%'`) is all the filtering the list screen needs. The chip
 * display in the editor splits/trims on commas. Keeping it a plain column
 * avoids a second table + migration for what is effectively a freeform label.
 */
@Entity(tableName = "teleprompter_script")
data class TeleprompterScript(
    /** Client-generated UUID — the stable identity for edits/deletes. */
    @PrimaryKey val id: String,
    /** User-visible title (single line). May be blank for an untitled draft. */
    val title: String,
    /** The script text the teleprompter scrolls. */
    val body: String,
    /**
     * Read-aloud duration estimate in whole seconds at [WPM_BASELINE], stored
     * so the list badge needs no per-frame recompute. Refreshed on each save
     * via [estimateDurationSecs].
     */
    val estimatedDurationSecs: Int,
    /** Comma-separated freeform tags. Empty string = no tags. */
    val tags: String = "",
    /**
     * Script format, persisted as a [ScriptFormat] enum *name* (string, not
     * ordinal — same forward-compat pattern as `FictionShelf.shelf`) so a new
     * format can be appended without a schema migration. Drives organization
     * and (future) format-aware behavior; the spoken-text parser
     * ([spokenText]) is format-agnostic so duration is correct regardless.
     */
    val format: String = ScriptFormat.FREEFORM.name,
    /** Wall-clock millis the script was first created. */
    val createdAt: Long,
    /** Wall-clock millis of the last edit. Equal to [createdAt] on first save;
     *  drives the `ORDER BY updatedAt DESC` list ordering. */
    val updatedAt: Long,
) {
    companion object {
        /**
         * Canonical reading pace for the stored [estimatedDurationSecs]. 150
         * wpm is a conventional spoken-narration midpoint (the teleprompter's
         * own default of `TELEPROMPTER_DEFAULT_WPM` = 130 is a slower
         * rehearsal pace; the stored estimate uses a neutral baseline so a
         * script's badge doesn't shift when the user nudges their live pace).
         */
        const val WPM_BASELINE: Int = 150

        /** A `[bracketed]` production cue — `[POST: JINGLE]`, `[pause]`, etc.
         *  May span lines, so DOT_MATCHES_ALL. Not spoken. */
        private val BRACKET_CUE = Regex("""\[[^\[\]]*]""", RegexOption.DOT_MATCHES_ALL)

        /** A full-line `====` banner. Toggles in/out of a non-spoken block
         *  (the top metadata block AND `====`-wrapped section headers). */
        private val BANNER_LINE = Regex("""^\s*={3,}\s*$""")

        /** A full-line `----` rule (metadata separator). Not spoken. */
        private val RULE_LINE = Regex("""^\s*-{3,}\s*$""")

        /** A speaker label alone on its line — `SHAWNA:`, `JEFF:`,
         *  `SHAWNA AND JEFF:`. All-caps name(s) + colon. Not spoken. */
        private val SPEAKER_LABEL = Regex("""^\s*[A-Z][A-Z0-9 .'&/()-]*:\s*$""")

        private val WHITESPACE = Regex("""\s+""")

        /** Total whitespace-delimited word count of [text] (markup included).
         *  Blank/whitespace-only → 0. */
        fun wordCount(text: String): Int =
            if (text.isBlank()) 0 else text.trim().split(WHITESPACE).size

        /**
         * The *spoken* portion of a show-format [body]: strips `[bracketed]`
         * production cues, `====`-delimited banner blocks (the top metadata /
         * "prompter notes" block AND `====`-wrapped section headers), `----`
         * rule lines, and whole-line `SPEAKER:` labels. Plain freeform text
         * (no markup) passes through unchanged. This is what the teleprompter
         * actually reads aloud, so the duration estimate counts these words —
         * not the cues/headers/labels (issue #1369, TechEmpower Show format).
         */
        fun spokenText(body: String): String {
            // 1. Remove [bracketed] cues first (they can span lines).
            val noCues = BRACKET_CUE.replace(body, " ")
            // 2. Line pass with a "inside ==== banner block" toggle. Each
            //    banner line flips the toggle, so both the top metadata block
            //    and every ====-wrapped section header fall inside it.
            var inBanner = false
            val kept = StringBuilder()
            for (line in noCues.lineSequence()) {
                if (BANNER_LINE.matches(line)) { inBanner = !inBanner; continue }
                if (inBanner) continue
                if (RULE_LINE.matches(line)) continue
                if (SPEAKER_LABEL.matches(line)) continue
                kept.append(line).append('\n')
            }
            return kept.toString().trim()
        }

        /** Word count of the spoken text only — see [spokenText]. */
        fun spokenWordCount(body: String): Int = wordCount(spokenText(body))

        /**
         * Estimated read-aloud duration of [body] in whole seconds at the given
         * pace, counting [spokenWordCount] (cues / headers / speaker labels
         * excluded). Rounds up so a short script never shows 0:00. Pure +
         * side-effect-free so it's unit-testable without a clock.
         */
        fun estimateDurationSecs(body: String, wpm: Int = WPM_BASELINE): Int {
            val words = spokenWordCount(body)
            if (words == 0) return 0
            val safeWpm = wpm.coerceAtLeast(1)
            // ceil(words / wpm * 60) without floating-point surprises.
            return ((words * 60) + safeWpm - 1) / safeWpm
        }
    }
}

/**
 * Issue #1369 — the kind of script, used for organization (and future
 * format-aware behavior). JP's three named buckets; persisted as the enum
 * `name` on [TeleprompterScript.format].
 */
enum class ScriptFormat(val label: String) {
    FREEFORM("Freeform"),
    SHORT("YouTube Short"),
    FULL_SHOW("Full Show"),
    ;

    companion object {
        /** Parse a stored [TeleprompterScript.format] string, falling back to
         *  [FREEFORM] for an unknown/legacy value. */
        fun fromName(name: String): ScriptFormat =
            entries.firstOrNull { it.name == name } ?: FREEFORM
    }
}
