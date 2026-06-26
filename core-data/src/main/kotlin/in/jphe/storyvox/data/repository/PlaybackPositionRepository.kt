package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.dao.ContinueListeningRow
import `in`.jphe.storyvox.data.db.dao.PlaybackDao
import `in`.jphe.storyvox.data.db.dao.RecentPlaybackRow
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import `in`.jphe.storyvox.data.repository.playback.RecentItem
import `in`.jphe.storyvox.data.repository.playback.SavedPosition
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface PlaybackPositionRepository {

    /**
     * Observe the fiction's resume point — its most-recently-played
     * chapter's row (issue #965). Multiple chapters of one fiction now have
     * independent rows; this surfaces the freshest one.
     */
    fun observePosition(fictionId: String): Flow<PlaybackPosition?>

    /**
     * Topmost "Continue listening" entry for the Library tab. The DAO layer
     * applies `LIMIT 1` so we never re-emit a library-sized list when only
     * the head row is consumed by the Library tile.
     */
    fun observeMostRecentContinueListening(): Flow<ContinueListeningEntry?>

    /**
     * Issue #793 — `fictionId → lastPlayedAt` map across every saved
     * playback position. Drives the Library "recently played" /
     * "longest unread" sort modes; emits on any upsert/delete, and
     * the consumer collapses the list into a map at the join site.
     */
    fun observeLastPlayedMap(): Flow<Map<String, Long>>

    suspend fun savePosition(
        fictionId: String,
        chapterId: String,
        charOffset: Int,
        paragraphIndex: Int,
        playbackSpeed: Float,
    )

    /**
     * Forget every saved chapter position for a fiction (issue #965 — clears
     * all per-chapter rows, not just the former single per-fiction row).
     */
    suspend fun clearPosition(fictionId: String)

    // ─── playback-layer accessors ─────────────────────────────────────────

    /**
     * Persist a resume point — playback-layer flavor. Updates `charOffset` and
     * `durationEstimateMs`; leaves `paragraphIndex` and `playbackSpeed`
     * untouched (the reader UI owns those). If no row exists yet for this
     * (fiction, chapter), defaults are written for the untouched fields.
     * Issue #965: the preserve-read is now scoped to the exact chapter, so
     * saving chapter N never inherits chapter N+1's paragraphIndex/speed.
     */
    suspend fun save(
        fictionId: String,
        chapterId: String,
        charOffset: Int,
        durationEstimateMs: Long,
    )

    /**
     * Issue #1127 — reset one chapter's resume point back to its head.
     *
     * Auto-advance is a *fresh* listen: finishing chapter N means chapter
     * N+1 starts at the beginning, even if the user partially listened to
     * N+1 earlier and a stale `charOffset` still sits in its
     * `playback_position` row. This upserts `charOffset = 0` /
     * `paragraphIndex = 0` for the exact `(fictionId, chapterId)` row and
     * bumps `updatedAt`, so the chapter becomes the freshest resume target
     * pointing at its head — every external surface (Library "Continue
     * listening", Auto/Wear browse, sync snapshot) then reads position 0,
     * never the stale middle-of-chapter offset. The per-chapter
     * [playbackSpeed] / [durationEstimateMs] are preserved (the listen is
     * fresh, but the user's speed for this chapter and its cached duration
     * estimate are not). Distinct from [clearPosition], which forgets the
     * WHOLE fiction's progress; this scopes to one chapter and keeps a row.
     */
    suspend fun resetToChapterStart(fictionId: String, chapterId: String)

    /**
     * Load the resume point for a given fiction — its most-recently-played
     * chapter's row (issue #965), or null if none. Picks the freshest
     * chapter so resume never lands on a stale future-chapter offset.
     */
    suspend fun load(fictionId: String): SavedPosition?

    /** Most-recently-played list, denormalized for the Auto/Wear menu. */
    suspend fun recent(limit: Int): List<RecentItem>
}

/** Public projection of the Continue-listening join. */
data class ContinueListeningEntry(
    val fiction: FictionSummary,
    val chapter: ChapterInfo,
    val charOffset: Int,
    val playbackSpeed: Float,
    val updatedAt: Long,
)

@Singleton
class PlaybackPositionRepositoryImpl @Inject constructor(
    private val dao: PlaybackDao,
) : PlaybackPositionRepository {

    override fun observePosition(fictionId: String): Flow<PlaybackPosition?> =
        dao.observe(fictionId)

    override fun observeMostRecentContinueListening(): Flow<ContinueListeningEntry?> =
        dao.observeMostRecentContinueListening().map { row -> row?.toEntry() }

    override fun observeLastPlayedMap(): Flow<Map<String, Long>> =
        dao.observeLastPlayedTimes().map { rows ->
            rows.associate { it.fictionId to it.updatedAt }
        }

    override suspend fun savePosition(
        fictionId: String,
        chapterId: String,
        charOffset: Int,
        paragraphIndex: Int,
        playbackSpeed: Float,
    ) {
        dao.upsert(
            PlaybackPosition(
                fictionId = fictionId,
                chapterId = chapterId,
                charOffset = charOffset,
                paragraphIndex = paragraphIndex,
                playbackSpeed = playbackSpeed,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun clearPosition(fictionId: String) {
        dao.delete(fictionId)
    }

    override suspend fun save(
        fictionId: String,
        chapterId: String,
        charOffset: Int,
        durationEstimateMs: Long,
    ) {
        val existing = dao.get(fictionId, chapterId)
        dao.upsert(
            PlaybackPosition(
                fictionId = fictionId,
                chapterId = chapterId,
                charOffset = charOffset,
                paragraphIndex = existing?.paragraphIndex ?: 0,
                playbackSpeed = existing?.playbackSpeed ?: 1.0f,
                durationEstimateMs = durationEstimateMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun resetToChapterStart(fictionId: String, chapterId: String) {
        // Read-then-upsert so the speed the user picked for THIS chapter and
        // its cached duration estimate survive the reset; only the resume
        // cursor (charOffset + paragraphIndex) snaps to the head. The upsert
        // replaces the row in place under the composite `(fictionId,
        // chapterId)` PK (#965) — no duplicate row — and the bumped
        // `updatedAt` makes it the freshest resume target for `load`/`observe`.
        val existing = dao.get(fictionId, chapterId)
        dao.upsert(
            PlaybackPosition(
                fictionId = fictionId,
                chapterId = chapterId,
                charOffset = 0,
                paragraphIndex = 0,
                playbackSpeed = existing?.playbackSpeed ?: 1.0f,
                durationEstimateMs = existing?.durationEstimateMs ?: 0L,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun load(fictionId: String): SavedPosition? =
        dao.get(fictionId)?.let {
            SavedPosition(
                fictionId = it.fictionId,
                chapterId = it.chapterId,
                charOffset = it.charOffset,
                durationEstimateMs = it.durationEstimateMs,
            )
        }

    override suspend fun recent(limit: Int): List<RecentItem> =
        dao.recent(limit).map(RecentPlaybackRow::toRecentItem)
}

private fun RecentPlaybackRow.toRecentItem(): RecentItem = RecentItem(
    fictionId = fictionId,
    chapterId = chapterId,
    bookTitle = bookTitle,
    chapterTitle = chapterTitle,
    coverUrl = coverUrl,
)

/**
 * Map the slim DAO row directly to the public projection. Equivalent to the
 * old `fiction.toSummary()` / `chapter.toInfo()` pair, but without forcing
 * the DAO to materialize the full [Fiction] + [Chapter] entities (which
 * carry multi-MB body text on the chapter side).
 */
private fun ContinueListeningRow.toEntry(): ContinueListeningEntry = ContinueListeningEntry(
    fiction = FictionSummary(
        id = f_id,
        sourceId = f_sourceId,
        title = f_title,
        author = f_author,
        coverUrl = f_coverUrl,
        description = f_description,
        tags = f_tags,
        status = f_status,
        chapterCount = f_chapterCount,
        rating = f_rating,
    ),
    chapter = ChapterInfo(
        id = c_id,
        sourceChapterId = c_sourceChapterId,
        index = c_index,
        title = c_title,
        publishedAt = c_publishedAt,
        wordCount = c_wordCount,
    ),
    charOffset = charOffset,
    playbackSpeed = playbackSpeed,
    updatedAt = updatedAt,
)
