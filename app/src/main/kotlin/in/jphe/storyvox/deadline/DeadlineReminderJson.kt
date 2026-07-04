package `in`.jphe.storyvox.deadline

import android.content.Context
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminder
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Issue #1515 — on-device JSON persistence for deadline reminders.
 *
 * A single small JSON file in the app's private `filesDir` — never
 * synced, never backed up (the file lives under filesDir and is excluded
 * from cloud backup / device-transfer by the app's `backup_rules` /
 * `data_extraction_rules`), never uploaded. Shared by
 * [JsonFileDeadlineReminderStore] (the DI seam impl) and
 * [DeadlineBootReceiver] (Hilt-free reboot re-arm), so the on-disk shape
 * lives in exactly one place.
 */
object DeadlineReminderJson {

    private const val FILE_NAME = "deadline_reminders.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** Read all persisted reminders. Returns empty on a missing / corrupt file. */
    fun readAll(context: Context): List<DeadlineReminder> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        json.decodeFromString<List<ReminderDto>>(f.readText()).map { it.toModel() }
    }.getOrDefault(emptyList())

    /** Overwrite the file with [reminders]. */
    fun writeAll(context: Context, reminders: List<DeadlineReminder>) {
        runCatching {
            file(context).writeText(json.encodeToString(reminders.map { it.toDto() }))
        }
    }

    @Serializable
    private data class ReminderDto(
        val id: String,
        val programId: String? = null,
        val label: String,
        val deadlineEpochDay: Long,
        val notificationTitle: String,
        val notificationBody: String,
        val offsetsDays: List<Int> = DeadlineReminder.DEFAULT_OFFSETS_DAYS,
        val createdEpochDay: Long,
    )

    private fun ReminderDto.toModel() = DeadlineReminder(
        id = id,
        programId = programId,
        label = label,
        deadlineEpochDay = deadlineEpochDay,
        notificationTitle = notificationTitle,
        notificationBody = notificationBody,
        offsetsDays = offsetsDays,
        createdEpochDay = createdEpochDay,
    )

    private fun DeadlineReminder.toDto() = ReminderDto(
        id = id,
        programId = programId,
        label = label,
        deadlineEpochDay = deadlineEpochDay,
        notificationTitle = notificationTitle,
        notificationBody = notificationBody,
        offsetsDays = offsetsDays,
        createdEpochDay = createdEpochDay,
    )
}
