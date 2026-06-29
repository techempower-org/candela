package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import `in`.jphe.storyvox.data.db.entity.CharacterVoice
import kotlinx.coroutines.flow.Flow

/**
 * Issue #1283 — read/write the per-fiction character→voice map.
 *
 * Consumed by the (forthcoming) Cast-assignment UI and the playback layer's
 * per-segment voice resolution. `REPLACE` on insert makes re-assigning a
 * character idempotent (one row per `(fictionId, characterName)`).
 */
@Dao
interface CharacterVoiceDao {

    /** Live map for one fiction — drives the Cast sheet + playback. */
    @Query("SELECT * FROM character_voice WHERE fictionId = :fictionId")
    fun observeForFiction(fictionId: String): Flow<List<CharacterVoice>>

    /** Synchronous snapshot for the playback load path. */
    @Query("SELECT * FROM character_voice WHERE fictionId = :fictionId")
    suspend fun forFiction(fictionId: String): List<CharacterVoice>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CharacterVoice)

    @Query("DELETE FROM character_voice WHERE fictionId = :fictionId AND characterName = :characterName")
    suspend fun delete(fictionId: String, characterName: String)

    @Query("DELETE FROM character_voice WHERE fictionId = :fictionId")
    suspend fun clearForFiction(fictionId: String)
}
