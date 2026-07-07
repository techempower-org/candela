package `in`.jphe.storyvox.data.notes

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Voice Notes (epic #1657, Phase 1) — one note row. A single table serves
 * BOTH a typed manual note and a recording-backed note via nullable fields.
 *
 * Mirrors the proven [TeleprompterScript][`in`.jphe.storyvox.data.db.entity.TeleprompterScript]
 * (v18) idioms: client-generated UUID [id] PK, **no foreign keys**, and
 * comma-separated [tags] with `LIKE` search (a junction table would be
 * over-engineering for a lightweight, single-user, local-first feature).
 *
 * Lives in the **separate [NotesDatabase]** (`notes.db`) — never a
 * `StoryvoxDatabase` table — so the whole file is cleanly backup-excludable
 * (spec §3.4/§3.7: Android backup excludes per DB file, not per table).
 */
@Entity(tableName = "note")
data class NoteEntity(
    /** Client-generated UUID — stable identity for edit/delete + a future syncer. */
    @PrimaryKey val id: String,
    /** User-visible title (single line). May be blank for an untitled note. */
    val title: String = "",
    /** Wall-clock millis the note was created. */
    val createdAt: Long,
    /** Wall-clock millis of the last edit; drives `ORDER BY updatedAt DESC`. */
    val updatedAt: Long,
    /** Comma-separated freeform tags (empty = none). Same idiom as TeleprompterScript. */
    val tags: String = "",
    /**
     * Absolute path to the recording (`filesDir/recordings/<id>.m4a`, written
     * by Phase 2a), or null for a typed-only note. The backup rules exclude
     * that directory (spec §3.7); [NotesRepository] deletes the file with the row.
     */
    val audioPath: String? = null,
    /** Recording length in millis, or null for a typed-only note. */
    val durationMs: Long? = null,
    /**
     * **Immutable** source-of-truth ASR output — re-transcribing replaces it
     * wholesale. Distinct from [body] (the user's editable content).
     */
    val transcript: String? = null,
    /** BCP-47-ish language of [transcript] (drives summary language), or null. */
    val transcriptLang: String? = null,
    /** Optional AI summary — only ever populated on explicit consent (Phase 3). */
    val summary: String? = null,
    /** **Editable** user body — typed/edited content, independent of [transcript]. */
    val body: String? = null,
    /** Transcription lifecycle. Persisted by name via [NotesConverters]. */
    val transcriptionStatus: TranscriptionStatus = TranscriptionStatus.NONE,
)

/**
 * Transcription lifecycle for a [NoteEntity]. `NONE` = a typed note with no
 * audio; `PENDING` = recorded, awaiting the worker; `RUNNING`/`DONE`/`FAILED`
 * track the WorkManager job (Phase 2b). Persisted by [TranscriptionStatus.name]
 * (a stored TEXT column), so appending a future state needs no schema migration.
 */
enum class TranscriptionStatus { NONE, PENDING, RUNNING, FAILED, DONE }
