package `in`.jphe.storyvox.data.repository.stats

import `in`.jphe.storyvox.data.db.dao.ActivityRow
import `in`.jphe.storyvox.data.db.dao.SourceStatRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1235 — pure calendar/aggregation math for the listening-stats
 * dashboard. Everything time-dependent is injected (a fixed [Instant] +
 * UTC [ZoneId]) so the day/hour buckets are exact and deterministic, no
 * Robolectric needed.
 */
class ListeningStatsCalculatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private fun millis(iso: String): Long = Instant.parse(iso).toEpochMilli()

    // ── currentStreak ──────────────────────────────────────────────────

    @Test
    fun `current streak counts back from today`() {
        val today = LocalDate.of(2026, 6, 28)
        val days = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, ListeningStatsCalculator.currentStreak(days, today))
    }

    @Test
    fun `current streak stops at the first gap`() {
        val today = LocalDate.of(2026, 6, 28)
        val days = setOf(today, today.minusDays(1), today.minusDays(3))
        assertEquals(2, ListeningStatsCalculator.currentStreak(days, today))
    }

    @Test
    fun `current streak stays live when today is idle but yesterday was active`() {
        val today = LocalDate.of(2026, 6, 28)
        val days = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, ListeningStatsCalculator.currentStreak(days, today))
    }

    @Test
    fun `current streak is zero when neither today nor yesterday is active`() {
        val today = LocalDate.of(2026, 6, 28)
        val days = setOf(today.minusDays(2), today.minusDays(3))
        assertEquals(0, ListeningStatsCalculator.currentStreak(days, today))
    }

    @Test
    fun `current streak is zero with no active days`() {
        assertEquals(0, ListeningStatsCalculator.currentStreak(emptySet(), LocalDate.of(2026, 6, 28)))
    }

    // ── longestStreak ──────────────────────────────────────────────────

    @Test
    fun `longest streak finds the best run anywhere in history`() {
        val d = LocalDate.of(2026, 6, 1)
        val days = setOf(d, d.plusDays(1), d.plusDays(2), d.plusDays(5), d.plusDays(6))
        assertEquals(3, ListeningStatsCalculator.longestStreak(days))
    }

    @Test
    fun `longest streak of isolated days is one`() {
        val d = LocalDate.of(2026, 6, 1)
        assertEquals(1, ListeningStatsCalculator.longestStreak(setOf(d, d.plusDays(2), d.plusDays(4))))
    }

    @Test
    fun `longest streak of empty history is zero`() {
        assertEquals(0, ListeningStatsCalculator.longestStreak(emptySet()))
    }

    // ── dayPartOf ──────────────────────────────────────────────────────

    @Test
    fun `day part boundaries map to the four buckets`() {
        assertEquals(DayPart.NIGHT, ListeningStatsCalculator.dayPartOf(4))
        assertEquals(DayPart.MORNING, ListeningStatsCalculator.dayPartOf(5))
        assertEquals(DayPart.MORNING, ListeningStatsCalculator.dayPartOf(11))
        assertEquals(DayPart.AFTERNOON, ListeningStatsCalculator.dayPartOf(12))
        assertEquals(DayPart.AFTERNOON, ListeningStatsCalculator.dayPartOf(16))
        assertEquals(DayPart.EVENING, ListeningStatsCalculator.dayPartOf(17))
        assertEquals(DayPart.EVENING, ListeningStatsCalculator.dayPartOf(21))
        assertEquals(DayPart.NIGHT, ListeningStatsCalculator.dayPartOf(22))
        assertEquals(DayPart.NIGHT, ListeningStatsCalculator.dayPartOf(0))
    }

    // ── assemble ───────────────────────────────────────────────────────

    @Test
    fun `assemble buckets today, week, trend, time-of-day and streaks`() {
        val now = Instant.parse("2026-06-28T12:00:00Z")
        val activity = listOf(
            // today (Sun 2026-06-28)
            ActivityRow(millis("2026-06-28T10:00:00Z"), completed = true, durationEstimateMs = 600_000),
            ActivityRow(millis("2026-06-28T20:00:00Z"), completed = true, durationEstimateMs = 300_000),
            ActivityRow(millis("2026-06-28T11:00:00Z"), completed = false, durationEstimateMs = 0), // active day, no time
            // earlier in the 7-day window
            ActivityRow(millis("2026-06-27T14:00:00Z"), completed = true, durationEstimateMs = 1_200_000),
            ActivityRow(millis("2026-06-25T08:00:00Z"), completed = true, durationEstimateMs = 600_000),
            // outside the 7-day window — counts toward time-of-day + longest streak inputs but NOT week
            ActivityRow(millis("2026-06-18T23:00:00Z"), completed = true, durationEstimateMs = 9_999_999),
        )
        val perSource = listOf(
            SourceStatRow("royalroad", finishedChapters = 4, estimatedMs = 2_700_000),
            SourceStatRow("gutenberg", finishedChapters = 1, estimatedMs = 9_999_999),
        )

        val stats = ListeningStatsCalculator.assemble(
            chaptersOpened = 6,
            chaptersFinished = 5,
            booksCompleted = 1,
            booksStarted = 2,
            wordsRead = 12_345,
            totalEstimatedMs = 13_399_999,
            perSource = perSource,
            activity = activity,
            now = now,
            zone = zone,
        )

        // Scalars pass through untouched.
        assertEquals(6, stats.chaptersOpened)
        assertEquals(5, stats.chaptersFinished)
        assertEquals(1, stats.booksCompleted)
        assertEquals(2, stats.booksStarted)
        assertEquals(12_345L, stats.wordsRead)
        assertEquals(13_399_999L, stats.totalEstimatedMs)

        // Today = the two completed rows dated 06-28 (the incomplete one adds 0).
        assertEquals(900_000L, stats.todayEstimatedMs)
        // Week = today + 06-27 + 06-25; the 06-18 row is outside the window.
        assertEquals(2_700_000L, stats.weekEstimatedMs)

        // Trend = 7 days, 06-22 .. 06-28, oldest first.
        assertEquals(ListeningStatsCalculator.TREND_DAYS, stats.weeklyActivity.size)
        assertEquals(LocalDate.of(2026, 6, 22), stats.weeklyActivity.first().date)
        assertEquals(LocalDate.of(2026, 6, 28), stats.weeklyActivity.last().date)
        val byDate = stats.weeklyActivity.associateBy { it.date }
        assertEquals(2, byDate[LocalDate.of(2026, 6, 28)]!!.finishedChapters)
        assertEquals(1, byDate[LocalDate.of(2026, 6, 27)]!!.finishedChapters)
        assertEquals(1, byDate[LocalDate.of(2026, 6, 25)]!!.finishedChapters)
        assertEquals(0, byDate[LocalDate.of(2026, 6, 24)]!!.finishedChapters)

        // Time of day over all FINISHED rows: morning 10:00 + 08:00 = 2,
        // afternoon 14:00 = 1, evening 20:00 = 1, night 23:00 = 1.
        val tod = stats.timeOfDay.associate { it.part to it.finishedChapters }
        assertEquals(2, tod[DayPart.MORNING])
        assertEquals(1, tod[DayPart.AFTERNOON])
        assertEquals(1, tod[DayPart.EVENING])
        assertEquals(1, tod[DayPart.NIGHT])

        // Active days {06-18, 06-25, 06-27, 06-28}: current = 06-28 + 06-27 = 2.
        assertEquals(2, stats.currentStreakDays)
        assertEquals(2, stats.longestStreakDays)

        // Per-source mapped 1:1, order preserved.
        assertEquals(2, stats.perSource.size)
        assertEquals("royalroad", stats.perSource.first().sourceId)
        assertEquals(4, stats.perSource.first().finishedChapters)
    }

    @Test
    fun `assemble of empty history yields a zeroed snapshot with a full week scaffold`() {
        val stats = ListeningStatsCalculator.assemble(
            chaptersOpened = 0,
            chaptersFinished = 0,
            booksCompleted = 0,
            booksStarted = 0,
            wordsRead = 0,
            totalEstimatedMs = 0,
            perSource = emptyList(),
            activity = emptyList(),
            now = Instant.parse("2026-06-28T12:00:00Z"),
            zone = zone,
        )
        assertEquals(0, stats.currentStreakDays)
        assertEquals(0L, stats.weekEstimatedMs)
        // The trend always scaffolds all 7 days (each zeroed) so the chart axis is stable.
        assertEquals(7, stats.weeklyActivity.size)
        assertEquals(0, stats.weeklyActivity.sumOf { it.finishedChapters })
        // All four day-parts always present.
        assertEquals(4, stats.timeOfDay.size)
        assertEquals(false, stats.hasData)
    }
}
