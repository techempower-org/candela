package `in`.jphe.storyvox.data.repository.stats

import `in`.jphe.storyvox.data.db.dao.ListeningStatsDao
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Issue #1235 — one-shot source for the listening-statistics dashboard.
 *
 * Snapshot semantics on purpose: a stats screen is opened occasionally
 * and read top-to-bottom, so a single suspend load is simpler and more
 * deterministic than wiring a dozen reactive `Flow`s together. The
 * caller (the ViewModel) re-loads on each visit.
 */
interface ListeningStatsRepository {

    /**
     * Compute the current snapshot. [now] / [zone] are injectable so the
     * "today", "this week", streak, and time-of-day buckets are testable
     * against a fixed clock; production callers pass the system clock.
     */
    suspend fun snapshot(
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ListeningStats
}

@Singleton
class ListeningStatsRepositoryImpl @Inject constructor(
    private val dao: ListeningStatsDao,
) : ListeningStatsRepository {

    override suspend fun snapshot(now: Instant, zone: ZoneId): ListeningStats =
        withContext(Dispatchers.IO) {
            // Sequential reads: ~7 cheap aggregate queries on a small,
            // single-connection DB. Not worth parallelizing for a screen
            // the user opens by hand.
            ListeningStatsCalculator.assemble(
                chaptersOpened = dao.chaptersOpened(),
                chaptersFinished = dao.chaptersFinished(),
                booksCompleted = dao.booksCompleted(),
                booksStarted = dao.booksStarted(),
                wordsRead = dao.wordsInFinishedChapters(),
                totalEstimatedMs = dao.estimatedFinishedMs(),
                perSource = dao.perSourceFinished(),
                activity = dao.activityRows(),
                now = now,
                zone = zone,
            )
        }
}
