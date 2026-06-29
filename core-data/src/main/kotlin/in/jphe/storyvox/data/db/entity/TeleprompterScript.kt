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

        /** Whitespace-delimited word count — the same notion the teleprompter
         *  paces on (one "word" per token). Blank/whitespace-only → 0. */
        fun wordCount(body: String): Int =
            if (body.isBlank()) 0 else body.trim().split(Regex("\\s+")).size

        /**
         * Estimated read-aloud duration of [body] in whole seconds at
         * [WPM_BASELINE]. Rounds up so a short script never shows 0:00 (a
         * single word still reads as ~1s). Pure + side-effect-free so it's
         * unit-testable without a clock.
         */
        fun estimateDurationSecs(body: String, wpm: Int = WPM_BASELINE): Int {
            val words = wordCount(body)
            if (words == 0) return 0
            val safeWpm = wpm.coerceAtLeast(1)
            // ceil(words / wpm * 60) without floating-point surprises.
            return ((words * 60) + safeWpm - 1) / safeWpm
        }
    }
}
