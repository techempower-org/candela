package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import `in`.jphe.storyvox.data.db.entity.TeleprompterScript
import kotlinx.coroutines.flow.Flow

/**
 * Issue #1369 — CRUD for user-authored teleprompter scripts. See
 * [TeleprompterScript] for the schema and the duration/word-count helpers.
 *
 * Upsert is an `@Insert(onConflict = REPLACE)` rather than `@Upsert` because
 * the screens save a fully-formed row each time (new + edit both build the
 * complete entity), so a replace-on-id is exactly the semantics we want and
 * keeps the generated SQL identical to the migration's expectations.
 */
@Dao
interface TeleprompterScriptDao {

    /** Insert a new script or replace an existing one with the same [TeleprompterScript.id]. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(script: TeleprompterScript)

    /** All scripts, most-recently-edited first — the list screen's default feed. */
    @Query("SELECT * FROM teleprompter_script ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TeleprompterScript>>

    @Query("SELECT * FROM teleprompter_script WHERE id = :id")
    suspend fun get(id: String): TeleprompterScript?

    @Query("DELETE FROM teleprompter_script WHERE id = :id")
    suspend fun delete(id: String)

    /**
     * Title-or-tags substring search, newest-edited first. Matches the list
     * screen's search bar; an empty query returns everything (the `LIKE
     * '%%'` degenerates to "match all"), so the screen can bind the same flow
     * for both the unfiltered and filtered states.
     */
    @Query(
        "SELECT * FROM teleprompter_script " +
            "WHERE title LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%' " +
            "ORDER BY updatedAt DESC",
    )
    fun search(query: String): Flow<List<TeleprompterScript>>
}
