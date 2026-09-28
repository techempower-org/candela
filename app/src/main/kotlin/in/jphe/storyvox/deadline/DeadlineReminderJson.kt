package `in`.jphe.storyvox.deadline

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminder
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
 *
 * Issue #1794 — the file is AES-256-GCM encrypted at rest with Jetpack
 * Security `EncryptedFile` under the Keystore [MasterKey], the same
 * scheme as the #1514 document wallet, because a benefits recert schedule
 * is sensitive. A plaintext `deadline_reminders.json` left by an older
 * build is migrated on first read and then deleted.
 */
object DeadlineReminderJson {

    private const val TAG = "DeadlineReminderJson"
    private const val FILE_NAME = "deadline_reminders.enc"

    /** Pre-#1794 plaintext file; migrated into [FILE_NAME] on first read. */
    private const val LEGACY_PLAINTEXT_NAME = "deadline_reminders.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Serializes file access. The store and the boot receiver can both
     * touch the file, and [writeAll] has to delete-then-recreate it
     * (EncryptedFile refuses to overwrite), so an unlocked concurrent read
     * could land in that gap and see no reminders.
     */
    private val lock = Any()

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun encrypted(context: Context, file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    /** Read all persisted reminders. Returns empty on a missing / corrupt file. */
    fun readAll(context: Context): List<DeadlineReminder> = synchronized(lock) {
        migrateLegacyPlaintext(context)
        runCatching {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val text = encrypted(context, f).openFileInput().use { it.readBytes().decodeToString() }
            json.decodeFromString<List<ReminderDto>>(text).map { it.toModel() }
        }.onFailure { Log.w(TAG, "reminders unreadable; treating as empty", it) }
            .getOrDefault(emptyList())
    }

    /** Overwrite the file with [reminders]. */
    fun writeAll(context: Context, reminders: List<DeadlineReminder>): Unit = synchronized(lock) {
        writeEncrypted(context, json.encodeToString(reminders.map { it.toDto() }))
    }

    private fun writeEncrypted(context: Context, text: String) {
        runCatching {
            val f = file(context)
            if (f.exists()) f.delete() // EncryptedFile won't overwrite
            encrypted(context, f).openFileOutput().use { it.write(text.encodeToByteArray()) }
        }.onFailure { Log.w(TAG, "reminders not saved", it) }
    }

    /**
     * Move an older build's plaintext file into the encrypted one. The
     * plaintext is deleted only once the encrypted copy reads back, so a
     * failed encrypt keeps the user's reminders rather than dropping them.
     */
    private fun migrateLegacyPlaintext(context: Context) {
        val legacy = File(context.filesDir, LEGACY_PLAINTEXT_NAME)
        if (!legacy.exists()) return
        runCatching {
            val text = legacy.readText()
            // An encrypted file that already exists is newer than the
            // plaintext (a write after an interrupted migration), so keep it.
            if (!file(context).exists()) writeEncrypted(context, text)
            // Throws, keeping the plaintext, unless the encrypted copy decrypts.
            encrypted(context, file(context)).openFileInput().use { it.readBytes() }
            legacy.delete()
        }.onFailure { Log.w(TAG, "plaintext reminders not migrated yet", it) }
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
