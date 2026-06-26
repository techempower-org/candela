package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.InboxEventDao
import `in`.jphe.storyvox.data.db.entity.InboxEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Issue #383 — cross-source Inbox surface.
 *
 * Three responsibilities:
 *  1. Observe the live event feed for the Library Inbox tab
 *     ([observeAll]).
 *  2. Observe the unread count for the tab's badge
 *     ([observeUnreadCount]).
 *  3. Accept inserts from backend pollers ([record]), coalescing
 *     duplicate "N new chapters in X" events for the same fiction so
 *     the feed doesn't flood after a long offline gap.
 *
 * Per-source mute toggles ARE NOT enforced here — the gating happens at
 * the call site so each backend's update emitter can short-circuit
 * before doing the work to build the event payload. The repo is the
 * write path of last resort; if you call [record], it writes.
 */
interface InboxRepository {

    /** All events, most-recent first. */
    fun observeAll(): Flow<List<InboxEvent>>

    /** Events newer than [afterTs]. */
    fun observeAfter(afterTs: Long): Flow<List<InboxEvent>>

    /** Live unread count — backs the Library Inbox tab badge. */
    fun observeUnreadCount(): Flow<Int>

    /**
     * Insert an event with coalescing. If an existing unread event
     * matches `(sourceId, fictionId)`, the [newChapterCount] delta is
     * *accumulated* into the row's stored count and the title is
     * rewritten to reflect the total — used by the new-chapter poller
     * to roll "1 new chapter" + "2 new chapters" into "3 new chapters"
     * across consecutive polls without flooding the feed.
     *
     * When [fictionId] is null (source-wide events like "KVMR live
     * now") we always insert a new row — there's nothing to coalesce
     * against. Same when no unread row exists for the fiction.
     *
     * [newChapterCount] is the delta from this poll (default 0 for
     * non-chapter events). On coalesce the stored count grows by this
     * amount; the [title] is ignored and rebuilt from the new total +
     * [fictionTitle]. On fresh insert the count seeds the row.
     *
     * Issue #1083: without numeric accumulation, the second poll's
     * title overwrote the first poll's, under-counting.
     */
    suspend fun record(
        sourceId: String,
        fictionId: String?,
        chapterId: String?,
        title: String,
        body: String?,
        ts: Long = System.currentTimeMillis(),
        deepLinkUri: String?,
        newChapterCount: Int = 0,
        fictionTitle: String? = null,
    ): Long

    /** Mark a single event read — fired on row tap. */
    suspend fun markRead(id: Long)

    /** Mark every event read — fired by the "Mark all read" action. */
    suspend fun markAllRead()
}

@Singleton
class InboxRepositoryImpl @Inject constructor(
    private val dao: InboxEventDao,
) : InboxRepository {

    override fun observeAll(): Flow<List<InboxEvent>> = dao.observeAll()

    override fun observeAfter(afterTs: Long): Flow<List<InboxEvent>> =
        dao.observeAfter(afterTs)

    override fun observeUnreadCount(): Flow<Int> = dao.observeUnreadCount()

    override suspend fun record(
        sourceId: String,
        fictionId: String?,
        chapterId: String?,
        title: String,
        body: String?,
        ts: Long,
        deepLinkUri: String?,
        newChapterCount: Int,
        fictionTitle: String?,
    ): Long {
        // Coalesce when we have a fictionId to look up against. The
        // common case: the new-chapter poller fires every N hours and
        // the user hasn't opened the Inbox yet — instead of stacking
        // "1 new chapter" then "2 new chapters" rows, accumulate the
        // count and rewrite the title to reflect the total.
        //
        // Issue #1083: the previous version blindly overwrote the title
        // with the single-poll delta, so poll 2's "1 new chapter"
        // clobbered poll 1's "2 new chapters". Now the stored
        // newChapterCount is the source of truth and the title is
        // derived from it.
        if (fictionId != null) {
            val existing = dao.latestUnreadForFiction(sourceId, fictionId)
            if (existing != null) {
                val totalCount = existing.newChapterCount + newChapterCount
                val plural = if (totalCount == 1) "chapter" else "chapters"
                val coalescedTitle = if (fictionTitle != null && totalCount > 0) {
                    "$totalCount new $plural in $fictionTitle"
                } else {
                    title
                }
                dao.coalesceInPlace(
                    id = existing.id,
                    title = coalescedTitle,
                    body = body,
                    ts = ts,
                    deepLinkUri = deepLinkUri,
                    additionalCount = newChapterCount,
                )
                return existing.id
            }
        }
        return dao.insert(
            InboxEvent(
                sourceId = sourceId,
                fictionId = fictionId,
                chapterId = chapterId,
                title = title,
                body = body,
                ts = ts,
                isRead = false,
                deepLinkUri = deepLinkUri,
                newChapterCount = newChapterCount,
            ),
        )
    }

    override suspend fun markRead(id: Long) = dao.markRead(id)
    override suspend fun markAllRead() = dao.markAllRead()
}
