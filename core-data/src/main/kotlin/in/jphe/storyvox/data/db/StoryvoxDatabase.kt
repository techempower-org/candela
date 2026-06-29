package `in`.jphe.storyvox.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import `in`.jphe.storyvox.data.db.converter.Converters
import `in`.jphe.storyvox.data.db.dao.AnnotationDao
import `in`.jphe.storyvox.data.db.dao.AuthDao
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.ChapterHistoryDao
import `in`.jphe.storyvox.data.db.dao.CharacterVoiceDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.dao.FictionMemoryDao
import `in`.jphe.storyvox.data.db.dao.FictionShelfDao
import `in`.jphe.storyvox.data.db.dao.InboxEventDao
import `in`.jphe.storyvox.data.db.dao.ListeningStatsDao
import `in`.jphe.storyvox.data.db.dao.LlmMessageDao
import `in`.jphe.storyvox.data.db.dao.LlmSessionDao
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.entity.Annotation
import `in`.jphe.storyvox.data.db.entity.AuthCookie
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import `in`.jphe.storyvox.data.db.entity.CharacterVoice
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.FictionMemoryEntry
import `in`.jphe.storyvox.data.db.entity.FictionShelf
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import `in`.jphe.storyvox.data.db.entity.LlmSession
import `in`.jphe.storyvox.data.db.entity.LlmStoredMessage
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition

@Database(
    entities = [
        Fiction::class,
        Chapter::class,
        PlaybackPosition::class,
        AuthCookie::class,
        // v3 (#81 AI integration) — multi-session chat tables.
        LlmSession::class,
        LlmStoredMessage::class,
        // v5 (#116 library shelves) — many-to-many junction.
        FictionShelf::class,
        // v6 (#158 reading history) — one row per (fiction, chapter)
        // pair, upserted on every open. Forever retention.
        ChapterHistory::class,
        // v8 (#383 cross-source inbox) — append-only event feed,
        // source-emitted notifications. No FK to fiction/chapter so
        // events survive removal of the underlying rows.
        InboxEvent::class,
        // v9 (#217 cross-fiction memory) — per-(fiction, name) entries
        // the AI extracts from its own chat replies. No FK to fiction
        // so removal of a book doesn't cascade-wipe its memory; the
        // cross-fiction lookup still surfaces "you read this name in
        // a book you no longer have."
        FictionMemoryEntry::class,
        // v14 (#999 highlights + notes) — text-range annotations beyond the
        // single per-chapter bookmark. FK CASCADE to fiction + chapter so a
        // removed book drops its highlights. Char-offset range into the
        // chapter body (same coordinate as the bookmark + sentence-highlight).
        Annotation::class,
        // v17 (#1283 per-character voice) — per-(fiction, character) voiceId
        // map. Additive junction table; FK CASCADE to fiction.
        CharacterVoice::class,
    ],
    // v11 (#965 per-chapter playback position) — PlaybackPosition PK changes
    // from `fictionId` to composite `(fictionId, chapterId)` so each chapter
    // remembers its own offset. Table-rebuild migration, zero data loss.
    //
    // v12 (#981 metadata back-fill) — adds `fiction.metadataBackfillFailedAt`
    // so synced placeholder rows that a source can't hydrate surface a
    // distinct "Couldn't load" state instead of an eternal "Loading…".
    // Purely additive nullable column.
    //
    // v13 (#989 rebuild-essential URL) — adds `fiction.sourceUrl`, the
    // original source URL for hash-id sources (Readability/RSS/EPUB
    // direct-download) so a synced placeholder can be rebuilt on a
    // second device. Purely additive nullable column.
    //
    // v14 (#999 highlights + notes) — new `annotation` table for text-range
    // highlights with optional notes. Purely additive table; no existing
    // table or row is touched.
    //
    // v15 (#1083 inbox unread-count) — adds `inbox_event.newChapterCount`
    // so coalesced polls accumulate chapter deltas instead of overwriting.
    // Purely additive NOT NULL DEFAULT 0 column.
    //
    // v16 (#1231 per-fiction playback speed) — adds `fiction.playbackSpeed`
    // so a book can pin its own speed (auto-restored on load) independent of
    // the global default. Purely additive nullable REAL column.
    //
    // v17 (#1283 per-character voice) — adds the `character_voice` table
    // (per-(fiction, character) → voiceId). Purely additive new table with a
    // CASCADE FK to fiction. See MIGRATION_16_17.
    version = 17,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class StoryvoxDatabase : RoomDatabase() {
    abstract fun fictionDao(): FictionDao
    abstract fun chapterDao(): ChapterDao
    abstract fun chapterHistoryDao(): ChapterHistoryDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun authDao(): AuthDao
    abstract fun llmSessionDao(): LlmSessionDao
    abstract fun llmMessageDao(): LlmMessageDao
    abstract fun fictionShelfDao(): FictionShelfDao
    abstract fun inboxEventDao(): InboxEventDao
    abstract fun fictionMemoryDao(): FictionMemoryDao
    abstract fun annotationDao(): AnnotationDao

    // Issue #1283 — per-character voice assignment map.
    abstract fun characterVoiceDao(): CharacterVoiceDao

    // Issue #1235 — read-only aggregate queries for the listening-stats
    // dashboard. No entity of its own; aggregates chapter_history +
    // playback_position + fiction + chapter, so no schema-version bump.
    abstract fun listeningStatsDao(): ListeningStatsDao

    companion object {
        const val NAME: String = "storyvox.db"
    }
}
