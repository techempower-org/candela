package `in`.jphe.storyvox.feature.stats

import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import `in`.jphe.storyvox.data.repository.stats.SourceShare
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1235 — pure presentation helpers for the listening-stats
 * dashboard. JVM-only (no Compose), per the :feature test discipline.
 */
class StatsFormattingTest {

    @Test
    fun `formatDuration renders hours and minutes`() {
        assertEquals("0m", StatsFormatting.formatDuration(0))
        assertEquals("0m", StatsFormatting.formatDuration(-5))
        assertEquals("<1m", StatsFormatting.formatDuration(30_000)) // 30s
        assertEquals("5m", StatsFormatting.formatDuration(5 * 60_000L))
        assertEquals("1h 30m", StatsFormatting.formatDuration(90 * 60_000L))
        assertEquals("2h", StatsFormatting.formatDuration(120 * 60_000L))
        assertEquals("10h", StatsFormatting.formatDuration(600 * 60_000L))
    }

    @Test
    fun `formatCompactNumber abbreviates thousands and millions`() {
        assertEquals("0", StatsFormatting.formatCompactNumber(0))
        assertEquals("0", StatsFormatting.formatCompactNumber(-3))
        assertEquals("999", StatsFormatting.formatCompactNumber(999))
        assertEquals("1k", StatsFormatting.formatCompactNumber(1_000))
        assertEquals("1.2k", StatsFormatting.formatCompactNumber(1_234))
        assertEquals("12k", StatsFormatting.formatCompactNumber(12_000))
        assertEquals("12.3k", StatsFormatting.formatCompactNumber(12_345))
        assertEquals("1M", StatsFormatting.formatCompactNumber(1_000_000))
        assertEquals("1.2M", StatsFormatting.formatCompactNumber(1_234_567))
    }

    @Test
    fun `sourceDisplayName maps known ids and prettifies unknown ones`() {
        assertEquals("Royal Road", StatsFormatting.sourceDisplayName("royalroad"))
        assertEquals("AO3", StatsFormatting.sourceDisplayName("ao3"))
        assertEquals("LibriVox", StatsFormatting.sourceDisplayName("librivox"))
        assertEquals("Project Gutenberg", StatsFormatting.sourceDisplayName("gutenberg"))
        // Unknown id → title-cased on separators.
        assertEquals("Foo Bar", StatsFormatting.sourceDisplayName("foo-bar"))
        assertEquals("Other", StatsFormatting.sourceDisplayName(""))
    }

    @Test
    fun `weekday initials follow Mon-Sun`() {
        assertEquals("M", StatsFormatting.weekdayInitial(1))
        assertEquals("T", StatsFormatting.weekdayInitial(2))
        assertEquals("W", StatsFormatting.weekdayInitial(3))
        assertEquals("T", StatsFormatting.weekdayInitial(4))
        assertEquals("F", StatsFormatting.weekdayInitial(5))
        assertEquals("S", StatsFormatting.weekdayInitial(6))
        assertEquals("S", StatsFormatting.weekdayInitial(7))
    }

    @Test
    fun `share summary only emits lines with non-zero figures`() {
        val populated = ListeningStats.EMPTY.copy(
            totalEstimatedMs = 15 * 60_000L,
            booksCompleted = 2,
            chaptersFinished = 42,
            currentStreakDays = 3,
            chaptersOpened = 50,
        )
        val text = StatsFormatting.shareSummary(populated, "Candela")
        assertTrue(text.startsWith("My listening on Candela:"))
        assertTrue(text.contains("≈ 15m listened"))
        assertTrue(text.contains("2 books finished"))
        assertTrue(text.contains("42 chapters read"))
        assertTrue(text.contains("3 day streak"))

        // An empty snapshot collapses to just the header — no zero-value noise.
        val empty = StatsFormatting.shareSummary(ListeningStats.EMPTY, "Candela")
        assertEquals("My listening on Candela:", empty)
    }

    @Test
    fun `share summary singularizes a one-book, one-day result`() {
        val one = ListeningStats.EMPTY.copy(booksCompleted = 1, currentStreakDays = 1, chaptersOpened = 1)
        val text = StatsFormatting.shareSummary(one, "Candela")
        assertTrue(text.contains("1 book finished"))
        assertTrue(text.contains("1 day streak"))
    }

    @Test
    fun `collapseSources keeps a short list intact`() {
        val shares = (1..MAX_DONUT_SLICES).map { SourceShare("s$it", finishedChapters = it, estimatedMs = it * 100L) }
        assertEquals(shares, collapseSources(shares))
    }

    @Test
    fun `collapseSources folds the tail into a single Other slice`() {
        val shares = (1..8).map { SourceShare("s$it", finishedChapters = 10 - it, estimatedMs = (10 - it) * 100L) }
        // finishedChapters: 9,8,7,6,5,4,3,2 (already descending)
        val collapsed = collapseSources(shares)
        assertEquals(MAX_DONUT_SLICES, collapsed.size)
        // First five untouched.
        assertEquals("s1", collapsed[0].sourceId)
        assertEquals("s5", collapsed[4].sourceId)
        // Last is the synthetic "Other" summing s6 (4) + s7 (3) + s8 (2) = 9 chapters.
        val other = collapsed.last()
        assertEquals(OTHER_SOURCE_ID, other.sourceId)
        assertEquals(9, other.finishedChapters)
        assertEquals(900L, other.estimatedMs)
    }
}
