package `in`.jphe.storyvox.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterHistory
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.db.entity.PlaybackPosition
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1235 — Room+Robolectric test for [ListeningStatsDao].
 *
 * The DAO is pure SQL aggregation over `chapter_history` +
 * `playback_position` + `fiction` + `chapter`; a typo in a JOIN
 * condition or a `HAVING` clause compiles fine and silently returns
 * wrong numbers, so the aggregates are the valuable surface to pin.
 */
@RunWith(RobolectricTestRunner::class)
// SDK pin matches PlaybackDaoTest (#1132) — Robolectric 4.16.1 ceilings at API 36.
@Config(manifest = Config.NONE, sdk = [36])
class ListeningStatsDaoTest {

    private lateinit var db: StoryvoxDatabase
    private lateinit var stats: ListeningStatsDao
    private lateinit var fictionDao: FictionDao
    private lateinit var chapterDao: ChapterDao
    private lateinit var historyDao: ChapterHistoryDao
    private lateinit var playbackDao: PlaybackDao

    private fun fiction(
        id: String,
        sourceId: String = "royalroad",
        chapterCount: Int = 0,
    ) = Fiction(
        id = id,
        sourceId = sourceId,
        title = "Title $id",
        author = "Author",
        firstSeenAt = 0L,
        metadataFetchedAt = 0L,
        chapterCount = chapterCount,
        inLibrary = true,
    )

    private fun chapter(
        id: String,
        fictionId: String,
        index: Int = 0,
        wordCount: Int? = 100,
    ) = Chapter(
        id = id,
        fictionId = fictionId,
        sourceChapterId = "src-$id",
        index = index,
        title = "Ch $id",
        wordCount = wordCount,
    )

    private suspend fun seedChapter(
        chapterId: String,
        fictionId: String,
        index: Int,
        wordCount: Int?,
        completed: Boolean,
        openedAt: Long,
        durationMs: Long?,
    ) {
        chapterDao.upsert(chapter(chapterId, fictionId, index, wordCount))
        historyDao.upsert(
            ChapterHistory(
                fictionId = fictionId,
                chapterId = chapterId,
                openedAt = openedAt,
                completed = completed,
            ),
        )
        if (durationMs != null) {
            playbackDao.upsert(
                PlaybackPosition(
                    fictionId = fictionId,
                    chapterId = chapterId,
                    durationEstimateMs = durationMs,
                    updatedAt = openedAt,
                ),
            )
        }
    }

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, StoryvoxDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stats = db.listeningStatsDao()
        fictionDao = db.fictionDao()
        chapterDao = db.chapterDao()
        historyDao = db.chapterHistoryDao()
        playbackDao = db.playbackDao()
    }

    @After
    fun tearDown() = db.close()

    /**
     * A populated library:
     *  - f1 (royalroad, 2 chapters): both finished, both with durations → a finished book.
     *  - f2 (gutenberg, 3 chapters): 1 finished, 1 opened-not-finished → started, not finished.
     */
    private suspend fun seedLibrary() {
        fictionDao.upsert(fiction("f1", sourceId = "royalroad", chapterCount = 2))
        fictionDao.upsert(fiction("f2", sourceId = "gutenberg", chapterCount = 3))
        seedChapter("c1", "f1", 0, wordCount = 100, completed = true, openedAt = 1_000L, durationMs = 600_000L)
        seedChapter("c2", "f1", 1, wordCount = 200, completed = true, openedAt = 2_000L, durationMs = 300_000L)
        seedChapter("c3", "f2", 0, wordCount = 50, completed = true, openedAt = 3_000L, durationMs = 1_200_000L)
        seedChapter("c4", "f2", 1, wordCount = 999, completed = false, openedAt = 4_000L, durationMs = 0L)
    }

    @Test
    fun `counts and sums aggregate finished chapters correctly`() = runTest {
        seedLibrary()
        assertEquals(4, stats.chaptersOpened())
        assertEquals(3, stats.chaptersFinished())
        assertEquals(2, stats.booksStarted())
        // c1 + c2 + c3 durations; c4 isn't finished so it's excluded.
        assertEquals(2_100_000L, stats.estimatedFinishedMs())
        // 100 + 200 + 50; c4's 999 is excluded (not finished).
        assertEquals(350L, stats.wordsInFinishedChapters())
    }

    @Test
    fun `books completed counts only fully-finished known-length fictions`() = runTest {
        seedLibrary()
        // f1: 2/2 finished → counts. f2: 1/3 finished → doesn't.
        assertEquals(1, stats.booksCompleted())
    }

    @Test
    fun `books completed ignores placeholder fictions with unknown length`() = runTest {
        // chapterCount = 0 (a synced "Loading…" placeholder) must never
        // count as a finished book even if its one cached chapter is done.
        fictionDao.upsert(fiction("p1", chapterCount = 0))
        seedChapter("pc1", "p1", 0, wordCount = 10, completed = true, openedAt = 10L, durationMs = 100L)
        assertEquals(0, stats.booksCompleted())
    }

    @Test
    fun `books completed tolerates a chapter count that shrank below finished`() = runTest {
        // Source now reports 1 chapter but the user finished 2 before the
        // recount — `>=` keeps it counted as finished.
        fictionDao.upsert(fiction("f3", chapterCount = 1))
        seedChapter("c5", "f3", 0, wordCount = 10, completed = true, openedAt = 10L, durationMs = 100L)
        seedChapter("c6", "f3", 1, wordCount = 10, completed = true, openedAt = 20L, durationMs = 100L)
        assertEquals(1, stats.booksCompleted())
    }

    @Test
    fun `per-source breakdown groups and orders by finished count`() = runTest {
        seedLibrary()
        val rows = stats.perSourceFinished()
        assertEquals(2, rows.size)
        // royalroad has 2 finished chapters → first; gutenberg 1 → second.
        assertEquals("royalroad", rows[0].sourceId)
        assertEquals(2, rows[0].finishedChapters)
        assertEquals(900_000L, rows[0].estimatedMs)
        assertEquals("gutenberg", rows[1].sourceId)
        assertEquals(1, rows[1].finishedChapters)
        assertEquals(1_200_000L, rows[1].estimatedMs)
    }

    @Test
    fun `activity rows project every history entry ordered by openedAt`() = runTest {
        seedLibrary()
        val rows = stats.activityRows()
        assertEquals(4, rows.size)
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), rows.map { it.openedAt })
        // The not-finished c4 carries its completed flag and a 0 duration.
        val c4 = rows.last()
        assertEquals(false, c4.completed)
        assertEquals(0L, c4.durationEstimateMs)
    }

    @Test
    fun `empty database returns zeroes, not nulls`() = runTest {
        assertEquals(0, stats.chaptersOpened())
        assertEquals(0, stats.chaptersFinished())
        assertEquals(0, stats.booksStarted())
        assertEquals(0L, stats.estimatedFinishedMs())
        assertEquals(0L, stats.wordsInFinishedChapters())
        assertEquals(0, stats.booksCompleted())
        assertEquals(emptyList<SourceStatRow>(), stats.perSourceFinished())
        assertEquals(emptyList<ActivityRow>(), stats.activityRows())
    }
}
