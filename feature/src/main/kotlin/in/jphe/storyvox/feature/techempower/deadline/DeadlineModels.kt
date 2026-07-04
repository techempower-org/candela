package `in`.jphe.storyvox.feature.techempower.deadline

import androidx.compose.runtime.Immutable
import java.time.LocalDate

/**
 * Issue #1515 — notice deadline keeper.
 *
 * Plain-JVM models shared by the pure [DeadlineDateExtractor], the
 * [DeadlineKeeperViewModel], and the scheduling / storage seams. No
 * Android or ML Kit types leak in here so the extraction + orchestration
 * logic stays unit-testable without Robolectric (same posture as the OCR
 * capture flow in #995).
 */

/**
 * One date the extractor pulled out of a scanned notice. The user
 * *confirms* one of these — the app never auto-commits a guess (per the
 * issue: "highlight candidates, user picks").
 *
 * @param date the parsed calendar date.
 * @param rawText the literal substring the date was parsed from
 *   (e.g. `"August 31, 2026"` / `"31 de agosto de 2026"`), shown so the
 *   user can recognise it.
 * @param snippet the surrounding line of OCR text, trimmed + collapsed,
 *   giving the user context to pick the right date.
 * @param cue the nearest deadline cue phrase found next to the date
 *   (`"respond by"`, `"vence el"`, …), or null when no cue was near.
 *   Candidates with a cue rank first.
 * @param isPast true when [date] is before "today" — surfaced but
 *   de-prioritised (a past date is almost never the reminder the user
 *   wants, but we don't hide it in case OCR mis-read the year).
 */
@Immutable
data class DateCandidate(
    val date: LocalDate,
    val rawText: String,
    val snippet: String,
    val cue: String?,
    val isPast: Boolean,
)

/**
 * A confirmed, locally-scheduled deadline reminder. Persisted on-device
 * only (a JSON file in the app's private storage — never synced, never
 * uploaded) and re-armed after a reboot.
 *
 * The notification copy ([notificationTitle] / [notificationBody]) is
 * user-editable and defaults to a program label + "deadline" with **no**
 * dollar amounts or case numbers, so nothing sensitive lands on the
 * lock-screen banner (per the issue's Privacy note).
 *
 * @param id stable unique id (also the base for the per-fire alarm
 *   request codes).
 * @param programId program-aware key (e.g. `"lifeline"`) or null for a
 *   free-form letter. Kept as a local string constant — see
 *   [DeadlinePrograms] — so this lane doesn't hard-depend on the
 *   screener corpus (#1517) landing first.
 * @param deadlineEpochDay the deadline date as [LocalDate.toEpochDay].
 * @param offsetsDays days-before-deadline to fire at (default 7 / 2 / 0).
 * @param createdEpochDay when the reminder was created (for the list UI).
 */
data class DeadlineReminder(
    val id: String,
    val programId: String?,
    val label: String,
    val deadlineEpochDay: Long,
    val notificationTitle: String,
    val notificationBody: String,
    val offsetsDays: List<Int> = DEFAULT_OFFSETS_DAYS,
    val createdEpochDay: Long,
) {
    val deadline: LocalDate get() = LocalDate.ofEpochDay(deadlineEpochDay)

    /** The calendar dates the reminders should fire on (deadline − each offset). */
    fun fireDates(): List<LocalDate> =
        offsetsDays.map { deadline.minusDays(it.toLong()) }.distinct()

    companion object {
        /** T-7, T-2, and day-of. */
        val DEFAULT_OFFSETS_DAYS = listOf(7, 2, 0)
    }
}
