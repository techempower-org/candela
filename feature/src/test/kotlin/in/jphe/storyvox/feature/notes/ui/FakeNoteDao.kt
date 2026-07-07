package `in`.jphe.storyvox.feature.notes.ui

import `in`.jphe.storyvox.data.notes.NoteDao
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [NoteDao] for the plain-JVM VM tests (the codebase's hand-rolled-
 * fake posture). Mirrors the real DAO's observable semantics: `observeAll` /
 * `search` are `updatedAt DESC`, and `search` is a case-insensitive substring
 * match over title / body / transcript (the SQLite `LIKE '%q%'` the real DAO
 * runs — an empty query matches every row via the non-null title).
 */
class FakeNoteDao : NoteDao {
    private val rows = MutableStateFlow<List<NoteEntity>>(emptyList())

    override suspend fun upsert(note: NoteEntity) {
        rows.value = rows.value.filterNot { it.id == note.id } + note
    }

    override fun observeAll(): Flow<List<NoteEntity>> =
        rows.map { list -> list.sortedByDescending { it.updatedAt } }

    override suspend fun get(id: String): NoteEntity? = rows.value.firstOrNull { it.id == id }

    override suspend fun all(): List<NoteEntity> = rows.value

    override suspend fun delete(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun search(query: String): Flow<List<NoteEntity>> =
        rows.map { list ->
            val q = query.lowercase()
            list.filter { note ->
                note.title.lowercase().contains(q) ||
                    (note.body?.lowercase()?.contains(q) == true) ||
                    (note.transcript?.lowercase()?.contains(q) == true)
            }.sortedByDescending { it.updatedAt }
        }

    // #1663 — column-scoped updates (disjoint write sets).
    override suspend fun updateTranscription(id: String, transcript: String?, status: TranscriptionStatus, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(transcript = transcript, transcriptionStatus = status, updatedAt = updatedAt) else it
        }
    }

    override suspend fun updateTranscriptionStatus(id: String, status: TranscriptionStatus, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(transcriptionStatus = status, updatedAt = updatedAt) else it
        }
    }

    override suspend fun updateEdit(id: String, title: String, body: String?, tags: String, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(title = title, body = body, tags = tags, updatedAt = updatedAt) else it
        }
    }

    override suspend fun updateSummary(id: String, summary: String?, updatedAt: Long) {
        rows.value = rows.value.map {
            if (it.id == id) it.copy(summary = summary, updatedAt = updatedAt) else it
        }
    }
}
