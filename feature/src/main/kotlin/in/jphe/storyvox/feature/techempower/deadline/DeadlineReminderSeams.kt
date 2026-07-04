package `in`.jphe.storyvox.feature.techempower.deadline

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/**
 * Issue #1515 — cross-module seams for the deadline keeper.
 *
 * The scheduling + persistence *implementations* live in `:app` (they
 * need `AlarmManager`, `Context`, and Android storage), but the
 * [DeadlineKeeperViewModel] only ever sees these interfaces — so the VM
 * stays plain-JVM and unit-testable with fakes (same pattern as the OCR
 * `OcrTextRecognizer` / `OcrDocumentStore` seams in #995).
 */

/** Arms / cancels the local, on-device reminders for a [DeadlineReminder]. */
interface DeadlineReminderScheduler {

    /** Arm a notification for every still-future fire date of [reminder]. */
    fun schedule(reminder: DeadlineReminder)

    /** Cancel all pending notifications for [reminder]. */
    fun cancel(reminder: DeadlineReminder)

    /**
     * Re-arm every reminder — used after a reboot (Android drops
     * scheduled alarms on restart) and after a bulk restore.
     */
    fun rescheduleAll(reminders: List<DeadlineReminder>)

    /**
     * Whether the OS currently lets us schedule *exact* alarms. On
     * Android 12+ this can be revoked; the UI surfaces a plain-language
     * "turn on exact reminders" hint when this is false (the reminders
     * still fire, just batched/inexact).
     */
    fun canScheduleExact(): Boolean
}

/**
 * On-device persistence for confirmed reminders. Stored in the app's
 * private storage only — never synced, never backed up to the cloud
 * (excluded in `xml/backup_rules` + `data_extraction_rules`), never
 * uploaded. Survives process death and (via the store + boot receiver)
 * device reboots.
 */
interface DeadlineReminderStore {

    /** Live list of reminders, newest deadline-relevant ordering handled by the UI. */
    fun reminders(): Flow<List<DeadlineReminder>>

    /** One-shot snapshot (used by the boot receiver to re-arm). */
    suspend fun all(): List<DeadlineReminder>

    /** Insert or replace by [DeadlineReminder.id]. */
    suspend fun upsert(reminder: DeadlineReminder)

    /** Remove by id. No-op if absent. */
    suspend fun delete(id: String)
}

/**
 * Injectable "what is today" so the VM's past-date logic is deterministic
 * in tests. Production binding returns [LocalDate.now]; tests pass a fixed
 * date. A tiny seam beats reaching for a full `java.time.Clock` binding
 * that could collide with another module's provider.
 */
fun interface DeadlineClock {
    fun today(): LocalDate
}
