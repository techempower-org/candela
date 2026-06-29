package `in`.jphe.storyvox.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * Issue #1283 — per-character voice assignment. One row = one character in
 * one fiction is narrated by one voice. A fiction with three assigned
 * characters has three rows, keyed by `(fictionId, characterName)`.
 *
 * `voiceId` is a [in.jphe.storyvox] VoiceCatalog id (e.g.
 * `kokoro_alice_en_GB_20`, `azure_en_US_AvaDragonHDLatestNeural`). The
 * resolution order at playback time is **character voice → the fiction's
 * narrator default (`Fiction.pinnedVoiceId`) → the global active voice**
 * (see `CharacterVoiceResolver`).
 *
 * `ON DELETE CASCADE` on the fiction FK drops a book's character mappings
 * when the book is purged — mirrors [FictionShelf]. The composite PK is
 * `fictionId`-first, so it doubles as the index the FK needs for cascade
 * deletes (no separate `@Index` required).
 */
@Entity(
    tableName = "character_voice",
    primaryKeys = ["fictionId", "characterName"],
    foreignKeys = [
        ForeignKey(
            entity = Fiction::class,
            parentColumns = ["id"],
            childColumns = ["fictionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CharacterVoice(
    val fictionId: String,
    val characterName: String,
    val voiceId: String,
)
