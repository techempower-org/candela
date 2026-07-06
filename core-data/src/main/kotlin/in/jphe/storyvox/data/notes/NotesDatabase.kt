package `in`.jphe.storyvox.data.notes

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * Voice Notes (#1657, Phase 1) — a **separate** Room database (`notes.db`),
 * deliberately NOT tables in `StoryvoxDatabase`. Rationale (spec §3.4/§3.7):
 * Android backup excludes per **DB file**, not per table, so a dedicated file
 * is the only way to keep note data off cloud-backup + device-transfer without
 * also killing library/settings backup. It also keeps notes off
 * `StoryvoxDatabase`'s migration ladder (no v20 bump).
 *
 * Ships at its own **v1** (fresh DB, no data migration). `exportSchema = true`
 * → the `1.json` fixture is generated via the ubox0 KSP workflow (#1640) and
 * committed, so the first migration (v1→v2) has a MigrationTest baseline.
 *
 * **Downgrade policy (deliberately deferred):** unlike `StoryvoxDatabase`,
 * this does NOT `fallbackToDestructiveMigrationOnDowngrade()` — notes are
 * irreplaceable user data (they don't rehydrate from a backend like the
 * library does), so silently wiping them on an APK downgrade is unacceptable.
 * At v1 there are no migrations, so the question is moot; when the first
 * migration lands, choose an explicit downgrade policy then (see [DataModule]).
 */
@Database(entities = [NoteEntity::class], version = 1, exportSchema = true)
@TypeConverters(NotesConverters::class)
abstract class NotesDatabase : RoomDatabase() {
    abstract fun noteDao(): NoteDao

    companion object {
        const val NAME: String = "notes.db"
    }
}

/**
 * Room type converters for [NotesDatabase]. [TranscriptionStatus] is stored by
 * name (a TEXT column); an unknown stored value (e.g. a downgrade that predates
 * a future state) decodes to [TranscriptionStatus.NONE] rather than crashing.
 */
internal class NotesConverters {
    @TypeConverter
    fun statusToName(status: TranscriptionStatus): String = status.name

    @TypeConverter
    fun nameToStatus(name: String): TranscriptionStatus =
        runCatching { TranscriptionStatus.valueOf(name) }.getOrDefault(TranscriptionStatus.NONE)
}
