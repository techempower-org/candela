package `in`.jphe.storyvox.data.db.dao

import androidx.room.Dao
import androidx.room.Query

/**
 * Issue #1235 — listening-statistics aggregation DAO.
 *
 * Read-only. Every query here is a pure aggregate over tables that
 * *already exist* — `chapter_history` (#158 reading breadcrumb),
 * `playback_position` (#965 per-chapter offset + duration estimate),
 * `fiction`, and `chapter`. There is intentionally **no** new entity
 * and **no** schema migration: the stats dashboard derives everything
 * from data the playback layer is already writing, so it can't drift
 * out of sync with a separate recording pipeline and adds zero cost to
 * the playback hot path.
 *
 * ## What's honest here, and what's an estimate
 *
 * `chapter_history.completed` / `openedAt` are ground truth — they're
 * stamped by [HistoryRepository] on real open / end-of-chapter events.
 * Counts and the activity timeline built from them are exact.
 *
 * "Time listened", by contrast, is an **estimate**: there is no
 * per-session stopwatch in the schema (the #1235 issue body notes a
 * future `ListeningSession` entity would give true wall-clock time).
 * We approximate it as the summed `playback_position.durationEstimateMs`
 * of the chapters the user actually *finished* — i.e. "the duration of
 * the audio content you've completed". The UI labels every time figure
 * with a "≈" so the estimate never masquerades as a measurement.
 *
 * ## Why bucketing isn't done in SQL
 *
 * Day / hour bucketing (streak, weekly trend, time-of-day) needs the
 * *device's* local calendar, and we want it unit-testable with a fixed
 * clock and zone. SQLite's `strftime(..., 'localtime')` would bind the
 * result to the JVM's default zone at query time and can't be pinned
 * from a test. So the heavy SUM/COUNT aggregates stay in SQL (they're
 * zone-independent) and the calendar math happens in
 * [ListeningStatsCalculator] over the raw [ActivityRow] timestamps.
 */
@Dao
interface ListeningStatsDao {

    /** Total chapters the user has ever opened (one row per fiction/chapter). */
    @Query("SELECT COUNT(*) FROM chapter_history")
    suspend fun chaptersOpened(): Int

    /** Chapters the user has read/listened to the end of. */
    @Query("SELECT COUNT(*) FROM chapter_history WHERE completed = 1")
    suspend fun chaptersFinished(): Int

    /** Distinct fictions the user has ever opened a chapter of ("books started"). */
    @Query("SELECT COUNT(DISTINCT fictionId) FROM chapter_history")
    suspend fun booksStarted(): Int

    /**
     * Estimated audio time of every finished chapter, in ms. Joins each
     * completed history row to its saved playback position to recover the
     * chapter's `durationEstimateMs`. Chapters finished before a position
     * was ever saved (no matching row) contribute 0 — an under-count we
     * accept rather than fabricate.
     */
    @Query(
        """
        SELECT COALESCE(SUM(p.durationEstimateMs), 0)
          FROM chapter_history h
          JOIN playback_position p
            ON p.fictionId = h.fictionId AND p.chapterId = h.chapterId
         WHERE h.completed = 1
        """,
    )
    suspend fun estimatedFinishedMs(): Long

    /** Total words across finished chapters (chapter bodies that lack a word count contribute 0). */
    @Query(
        """
        SELECT COALESCE(SUM(c.wordCount), 0)
          FROM chapter_history h
          JOIN chapter c ON c.id = h.chapterId
         WHERE h.completed = 1
        """,
    )
    suspend fun wordsInFinishedChapters(): Long

    /**
     * Books the user has *fully* finished: every chapter of a
     * known-length fiction marked completed. `f.chapterCount > 0` skips
     * placeholder rows whose length we don't know yet (a synced
     * "Loading…" row), so they can't masquerade as a finished book. The
     * `>=` (not `=`) tolerates a stale chapter count that shrank after
     * the user had already finished more chapters than the source now
     * reports.
     */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT h.fictionId
              FROM chapter_history h
              JOIN fiction f ON f.id = h.fictionId
             WHERE h.completed = 1 AND f.chapterCount > 0
             GROUP BY h.fictionId
            HAVING COUNT(*) >= MAX(f.chapterCount)
        )
        """,
    )
    suspend fun booksCompleted(): Int

    /**
     * Per-source breakdown over finished chapters — count + estimated ms,
     * busiest source first. Backs the "listening by source" donut. The
     * LEFT JOIN keeps a source represented even when none of its finished
     * chapters has a saved duration (it just contributes 0 ms).
     */
    @Query(
        """
        SELECT f.sourceId            AS sourceId,
               COUNT(*)              AS finishedChapters,
               COALESCE(SUM(p.durationEstimateMs), 0) AS estimatedMs
          FROM chapter_history h
          JOIN fiction f ON f.id = h.fictionId
          LEFT JOIN playback_position p
            ON p.fictionId = h.fictionId AND p.chapterId = h.chapterId
         WHERE h.completed = 1
         GROUP BY f.sourceId
         ORDER BY finishedChapters DESC, estimatedMs DESC
        """,
    )
    suspend fun perSourceFinished(): List<SourceStatRow>

    /**
     * Raw activity timeline — one row per chapter-history entry, carrying
     * its last-open timestamp, completion flag, and (when known) duration
     * estimate. [ListeningStatsCalculator] buckets these into the streak,
     * the 7-day trend, the today/week time totals, and the time-of-day
     * histogram using a caller-supplied clock + zone.
     *
     * Caveat inherited from the #158 schema: `chapter_history` keeps a
     * single row per (fiction, chapter) and updates `openedAt` in place
     * on re-open, so a re-listened chapter moves to "today" rather than
     * adding a second day. Trends therefore reflect *last-touched* days,
     * not an append-only listening log — the honest limit of deriving
     * stats without a dedicated session table.
     */
    @Query(
        """
        SELECT h.openedAt   AS openedAt,
               h.completed  AS completed,
               COALESCE(p.durationEstimateMs, 0) AS durationEstimateMs
          FROM chapter_history h
          LEFT JOIN playback_position p
            ON p.fictionId = h.fictionId AND p.chapterId = h.chapterId
         ORDER BY h.openedAt
        """,
    )
    suspend fun activityRows(): List<ActivityRow>
}

/** Per-source finished-chapter aggregate row. */
data class SourceStatRow(
    val sourceId: String,
    val finishedChapters: Int,
    val estimatedMs: Long,
)

/**
 * One chapter-history entry projected for stats bucketing. Slim on
 * purpose — only the three fields the calendar math needs, never the
 * chapter body blobs.
 */
data class ActivityRow(
    val openedAt: Long,
    val completed: Boolean,
    val durationEstimateMs: Long,
)
