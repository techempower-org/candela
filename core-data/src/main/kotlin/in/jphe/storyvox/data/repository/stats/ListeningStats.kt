package `in`.jphe.storyvox.data.repository.stats

import `in`.jphe.storyvox.data.db.dao.ActivityRow
import `in`.jphe.storyvox.data.db.dao.SourceStatRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Issue #1235 — immutable snapshot backing the listening-statistics
 * dashboard. Assembled by [ListeningStatsCalculator.assemble] from the
 * raw `ListeningStatsDao` outputs plus a clock + zone, so the whole
 * thing is computed in one pure step that's trivial to unit-test.
 *
 * Time figures are estimates (see `ListeningStatsDao` kdoc) — the UI
 * prefixes them with "≈".
 */
data class ListeningStats(
    /** Estimated audio time of all finished chapters, ms. */
    val totalEstimatedMs: Long,
    /** Estimated time finished *today* (device-local day), ms. */
    val todayEstimatedMs: Long,
    /** Estimated time finished in the last 7 days inclusive, ms. */
    val weekEstimatedMs: Long,
    val chaptersOpened: Int,
    val chaptersFinished: Int,
    val booksCompleted: Int,
    val booksStarted: Int,
    val wordsRead: Long,
    /** Consecutive active days ending today (or yesterday, if today is idle). */
    val currentStreakDays: Int,
    /** Best-ever run of consecutive active days. */
    val longestStreakDays: Int,
    /** Last 7 calendar days, oldest first — backs the weekly bar chart. */
    val weeklyActivity: List<DayActivity>,
    /** The four day-parts in fixed order — backs the time-of-day chart. */
    val timeOfDay: List<TimeOfDayBucket>,
    /** Finished-chapter breakdown per source, busiest first — backs the donut. */
    val perSource: List<SourceShare>,
) {
    /** True once there's anything worth charting (the screen shows an empty state otherwise). */
    val hasData: Boolean get() = chaptersOpened > 0

    companion object {
        val EMPTY = ListeningStats(
            totalEstimatedMs = 0L,
            todayEstimatedMs = 0L,
            weekEstimatedMs = 0L,
            chaptersOpened = 0,
            chaptersFinished = 0,
            booksCompleted = 0,
            booksStarted = 0,
            wordsRead = 0L,
            currentStreakDays = 0,
            longestStreakDays = 0,
            weeklyActivity = emptyList(),
            timeOfDay = emptyList(),
            perSource = emptyList(),
        )
    }
}

/** One day on the weekly trend: how many chapters were finished and the estimated time. */
data class DayActivity(
    val date: LocalDate,
    val finishedChapters: Int,
    val estimatedMs: Long,
)

/** Coarse part of the day a chapter was last opened in. */
enum class DayPart { MORNING, AFTERNOON, EVENING, NIGHT }

/** Finished-chapter count within one [DayPart]. */
data class TimeOfDayBucket(
    val part: DayPart,
    val finishedChapters: Int,
)

/** Finished-chapter share for one source. */
data class SourceShare(
    val sourceId: String,
    val finishedChapters: Int,
    val estimatedMs: Long,
)

/**
 * Pure calendar/aggregation math for [ListeningStats]. No Android, no
 * Room, no system clock — everything that touches "now" or a time zone
 * is passed in, so the unit tests pin a fixed [Instant] + [ZoneId] and
 * assert exact buckets.
 */
object ListeningStatsCalculator {

    /** Number of days shown on the weekly trend (today + the previous six). */
    const val TREND_DAYS: Int = 7

    /**
     * Assemble a full snapshot from the raw DAO outputs.
     *
     * @param activity every chapter-history row (for the calendar math)
     * @param now the moment "today" is anchored to
     * @param zone the device-local zone that defines day/hour boundaries
     */
    fun assemble(
        chaptersOpened: Int,
        chaptersFinished: Int,
        booksCompleted: Int,
        booksStarted: Int,
        wordsRead: Long,
        totalEstimatedMs: Long,
        perSource: List<SourceStatRow>,
        activity: List<ActivityRow>,
        now: Instant,
        zone: ZoneId,
    ): ListeningStats {
        val today = now.atZone(zone).toLocalDate()
        val weekStart = today.minusDays((TREND_DAYS - 1).toLong())

        // Any opened chapter marks the day "active" for streak purposes —
        // the most generous honest reading of "a day you were reading",
        // since we have no per-minute signal to apply the issue's "≥15min".
        val activeDays = activity.mapTo(HashSet()) { localDate(it.openedAt, zone) }

        // Finished-chapter contributions, bucketed once and reused.
        val finished = activity.asSequence().filter { it.completed }
        var todayMs = 0L
        var weekMs = 0L
        val perDayFinished = HashMap<LocalDate, Int>()
        val perDayMs = HashMap<LocalDate, Long>()
        val perPart = IntArray(DayPart.entries.size)
        for (row in finished) {
            val zdt = Instant.ofEpochMilli(row.openedAt).atZone(zone)
            val day = zdt.toLocalDate()
            if (day == today) todayMs += row.durationEstimateMs
            if (!day.isBefore(weekStart) && !day.isAfter(today)) {
                weekMs += row.durationEstimateMs
                perDayFinished[day] = (perDayFinished[day] ?: 0) + 1
                perDayMs[day] = (perDayMs[day] ?: 0L) + row.durationEstimateMs
            }
            perPart[dayPartOf(zdt.hour).ordinal]++
        }

        val weekly = (0 until TREND_DAYS).map { offset ->
            val day = weekStart.plusDays(offset.toLong())
            DayActivity(
                date = day,
                finishedChapters = perDayFinished[day] ?: 0,
                estimatedMs = perDayMs[day] ?: 0L,
            )
        }

        val timeOfDay = DayPart.entries.map { part ->
            TimeOfDayBucket(part = part, finishedChapters = perPart[part.ordinal])
        }

        return ListeningStats(
            totalEstimatedMs = totalEstimatedMs,
            todayEstimatedMs = todayMs,
            weekEstimatedMs = weekMs,
            chaptersOpened = chaptersOpened,
            chaptersFinished = chaptersFinished,
            booksCompleted = booksCompleted,
            booksStarted = booksStarted,
            wordsRead = wordsRead,
            currentStreakDays = currentStreak(activeDays, today),
            longestStreakDays = longestStreak(activeDays),
            weeklyActivity = weekly,
            timeOfDay = timeOfDay,
            perSource = perSource.map { SourceShare(it.sourceId, it.finishedChapters, it.estimatedMs) },
        )
    }

    /**
     * Consecutive active days ending at [today]. If today has no activity
     * yet but yesterday did, the streak is still "live" and anchored to
     * yesterday (you haven't broken it by not having read yet today).
     */
    fun currentStreak(activeDays: Set<LocalDate>, today: LocalDate): Int {
        var anchor = when {
            activeDays.contains(today) -> today
            activeDays.contains(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }
        var count = 0
        while (activeDays.contains(anchor)) {
            count++
            anchor = anchor.minusDays(1)
        }
        return count
    }

    /** Longest run of consecutive active days anywhere in the history. */
    fun longestStreak(activeDays: Set<LocalDate>): Int {
        if (activeDays.isEmpty()) return 0
        val sorted = activeDays.sorted()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1].plusDays(1)) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /**
     * Day-part bucket boundaries: morning 05–11, afternoon 12–16,
     * evening 17–21, night 22–04. Matches the issue's
     * "morning/afternoon/evening/night" split.
     */
    fun dayPartOf(hour: Int): DayPart = when (hour) {
        in 5..11 -> DayPart.MORNING
        in 12..16 -> DayPart.AFTERNOON
        in 17..21 -> DayPart.EVENING
        else -> DayPart.NIGHT
    }

    private fun localDate(epochMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
}
