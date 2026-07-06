package `in`.jphe.storyvox.data.notes

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Voice Notes (#1657, Phase 1) — CRUD + search for [NoteEntity]. Mirrors
 * [TeleprompterScriptDao][`in`.jphe.storyvox.data.db.dao.TeleprompterScriptDao]:
 * `@Insert(onConflict = REPLACE)` upsert (screens save a fully-formed row),
 * `Flow` reads, and a `LIKE` substring search.
 */
@Dao
interface NoteDao {

    /** Insert a new note or replace an existing one with the same [NoteEntity.id]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: NoteEntity)

    /** All notes, most-recently-edited first — the list screen's default feed. */
    @Query("SELECT * FROM note ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM note WHERE id = :id")
    suspend fun get(id: String): NoteEntity?

    /** One-shot snapshot of every row — used by the orphan-audio sweep. */
    @Query("SELECT * FROM note")
    suspend fun all(): List<NoteEntity>

    @Query("DELETE FROM note WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Substring search over title / body / transcript, newest-edited first.
     * An empty [query] degenerates to `LIKE '%%'`, which matches every row via
     * the non-null [NoteEntity.title] column — so the screen binds one flow for
     * both filtered and unfiltered states. (`NULL LIKE …` is `NULL`, so a null
     * body/transcript simply doesn't contribute a match — correct.)
     */
    @Query(
        "SELECT * FROM note " +
            "WHERE title LIKE '%' || :query || '%' " +
            "OR body LIKE '%' || :query || '%' " +
            "OR transcript LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC",
    )
    fun search(query: String): Flow<List<NoteEntity>>
}
