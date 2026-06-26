package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import kotlinx.coroutines.flow.Flow

/**
 * Issue #383 — DAO for the cross-source Inbox.
 *
 * No denormalized join here (unlike [ChapterHistoryDao]) — the event row
 * already carries its display strings (`title` / `body` / `deepLinkUri`),
 * so the UI renders directly off [InboxEvent]. That's intentional: the
 * Inbox is meant to survive fiction/chapter deletions, so we don't want
 * the UI to depend on those parent tables being present.
 */
@Dao
interface InboxEventDao {

    /** All events, most-recent first. Powers the Inbox feed. */
    @Query("SELECT * FROM inbox_event ORDER BY ts DESC")
    fun observeAll(): Flow<List<InboxEvent>>

    /**
     * Events newer than [afterTs] — for incremental "what's new since I
     * last looked" queries. The product surface uses [observeAll] today;
     * this hook is here so a future widget or wear surface can pull a
     * delta without re-scanning the whole feed.
     */
    @Query("SELECT * FROM inbox_event WHERE ts > :afterTs ORDER BY ts DESC")
    fun observeAfter(afterTs: Long): Flow<List<InboxEvent>>

    /**
     * Unread count — `WHERE isRead = 0`. Drives the Library Inbox tab
     * badge. Index on `isRead` makes this constant-time-ish even for
     * large feeds.
     */
    @Query("SELECT COUNT(*) FROM inbox_event WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    /** Insert one event. */
    @Insert
    suspend fun insert(event: InboxEvent): Long

    /**
     * Mark a single event read. Used when the user taps the row — the
     * deep-link navigation is the implicit "I've seen this" signal.
     */
    @Query("UPDATE inbox_event SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    /**
     * Mark every event read. Used by the "Mark all read" action in the
     * Inbox top bar.
     */
    @Query("UPDATE inbox_event SET isRead = 1 WHERE isRead = 0")
    suspend fun markAllRead()

    /**
     * Most-recent event for a (sourceId, fictionId) pair — used by the
     * repository's coalesce-on-insert logic to spot a fresh "N new
     * chapters" event for a fiction the user hasn't read yet and bump
     * the count instead of inserting a duplicate row.
     */
    @Query(
        """
        SELECT * FROM inbox_event
         WHERE sourceId = :sourceId
           AND fictionId = :fictionId
           AND isRead = 0
         ORDER BY ts DESC
         LIMIT 1
        """,
    )
    suspend fun latestUnreadForFiction(sourceId: String, fictionId: String): InboxEvent?

    /**
     * Coalesce a new poll's delta into an existing unread row. The
     * [additionalCount] is *added* to the stored `newChapterCount` so
     * consecutive polls accumulate ("2 new chapters" + "1 new chapter"
     * = 3, not 1). The [title] is rewritten by the caller to reflect
     * the new total.
     *
     * Issue #1083: the previous version overwrote the title with the
     * single-poll delta, losing the prior count.
     */
    @Query(
        """
        UPDATE inbox_event
           SET title = :title,
               body = :body,
               ts = :ts,
               deepLinkUri = :deepLinkUri,
               newChapterCount = newChapterCount + :additionalCount
         WHERE id = :id
        """,
    )
    suspend fun coalesceInPlace(
        id: Long,
        title: String,
        body: String?,
        ts: Long,
        deepLinkUri: String?,
        additionalCount: Int,
    )

    /** Diagnostic / test helper — full table delete. */
    @Query("DELETE FROM inbox_event")
    suspend fun deleteAll()
}
