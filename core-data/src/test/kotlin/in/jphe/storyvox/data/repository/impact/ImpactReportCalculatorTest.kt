package `in`.jphe.storyvox.data.repository.impact

import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import `in`.jphe.storyvox.data.repository.stats.SourceShare
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1463 — the coarsening + delta math is the load-bearing anonymization step, so
 * it's pinned exactly here. Pure JVM, no Android, no Robolectric.
 */
class ImpactReportCalculatorTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val HOUR_MS = 3_600_000L

    private fun stats(
        totalMs: Long = 0L,
        chapters: Int = 0,
        books: Int = 0,
        sources: List<SourceShare> = emptyList(),
    ): ListeningStats = ListeningStats.EMPTY.copy(
        totalEstimatedMs = totalMs,
        chaptersFinished = chapters,
        booksCompleted = books,
        perSource = sources,
    )

    private fun src(id: String, finished: Int): SourceShare =
        SourceShare(sourceId = id, finishedChapters = finished, estimatedMs = 0L)

    // ── coarsen ────────────────────────────────────────────────────────

    @Test
    fun `coarsen snaps to nearest bucket, ties round up`() {
        assertEquals(0, ImpactReportCalculator.coarsen(0, 5))
        assertEquals(0, ImpactReportCalculator.coarsen(2, 5))
        assertEquals(5, ImpactReportCalculator.coarsen(3, 5)) // 2.5 tie -> up
        assertEquals(5, ImpactReportCalculator.coarsen(7, 5))
        assertEquals(20, ImpactReportCalculator.coarsen(20, 5))
        assertEquals(20, ImpactReportCalculator.coarsen(22, 5))
    }

    @Test
    fun `coarsen with bucket of 1 is identity, negatives clamp to zero`() {
        assertEquals(5, ImpactReportCalculator.coarsen(5, 1))
        assertEquals(0, ImpactReportCalculator.coarsen(-3, 5))
    }

    @Test
    fun `coarsenHours rounds ms to whole hours then buckets`() {
        assertEquals(0, ImpactReportCalculator.coarsenHours(0L))
        assertEquals(0, ImpactReportCalculator.coarsenHours(HOUR_MS)) // 1h -> nearest 5 = 0
        assertEquals(5, ImpactReportCalculator.coarsenHours(3 * HOUR_MS)) // 3h -> 5
        assertEquals(15, ImpactReportCalculator.coarsenHours(14 * HOUR_MS)) // 14h -> 15
        assertEquals(20, ImpactReportCalculator.coarsenHours(22 * HOUR_MS)) // 22h -> 20
    }

    // ── coarsenSnapshot ─────────────────────────────────────────────────

    @Test
    fun `coarsenSnapshot buckets counts and keeps only sources with finished chapters`() {
        val totals = ImpactReportCalculator.coarsenSnapshot(
            stats(
                totalMs = 17 * HOUR_MS,
                chapters = 18,
                books = 3,
                sources = listOf(src("royalroad", 12), src("gutenberg", 6), src("wikipedia", 0)),
            ),
        )
        assertEquals(15, totals.hoursListened) // 17h -> 15
        assertEquals(20, totals.chaptersCompleted) // 18 -> 20
        assertEquals(3, totals.booksCompleted)
        assertEquals(setOf("royalroad", "gutenberg"), totals.sourceIds) // wikipedia dropped (0)
    }

    // ── buildReport (delta) ─────────────────────────────────────────────

    @Test
    fun `buildReport is the coarse delta since last share`() {
        val current = ImpactTotals(hoursListened = 20, chaptersCompleted = 30, booksCompleted = 6, sourceIds = setOf("a", "b", "c"))
        val last = ImpactTotals(hoursListened = 5, chaptersCompleted = 10, booksCompleted = 2, sourceIds = setOf("a"))
        val report = ImpactReportCalculator.buildReport(current, last, "2026-07", "1.9")
        assertEquals(15, report.hoursListenedBucket)
        assertEquals(20, report.chaptersCompletedBucket)
        assertEquals(4, report.booksCompletedBucket)
        assertEquals(listOf("b", "c"), report.sourcesUsed) // only newly-used, sorted
        assertEquals("2026-07", report.period)
        assertEquals("1.9", report.appVersion)
        assertEquals(ImpactReportCalculator.SCHEMA_VERSION, report.schema)
    }

    @Test
    fun `buildReport clamps at zero on reset or reinstall (never negative)`() {
        // current < lastShared (user cleared data / reinstalled)
        val current = ImpactTotals(hoursListened = 0, chaptersCompleted = 0, booksCompleted = 0, sourceIds = emptySet())
        val last = ImpactTotals(hoursListened = 25, chaptersCompleted = 40, booksCompleted = 8, sourceIds = setOf("a", "b"))
        val report = ImpactReportCalculator.buildReport(current, last, "2026-07", "1.9")
        assertEquals(0, report.hoursListenedBucket)
        assertEquals(0, report.chaptersCompletedBucket)
        assertEquals(0, report.booksCompletedBucket)
        assertEquals(emptyList<String>(), report.sourcesUsed)
    }

    @Test
    fun `first-ever share reports the full cumulative total against ZERO baseline`() {
        val current = ImpactReportCalculator.coarsenSnapshot(
            stats(totalMs = 12 * HOUR_MS, chapters = 22, books = 2, sources = listOf(src("gutenberg", 22))),
        )
        val report = ImpactReportCalculator.buildReport(current, ImpactTotals.ZERO, "2026-07", "1.9")
        assertEquals(10, report.hoursListenedBucket) // 12h -> 10
        assertEquals(20, report.chaptersCompletedBucket) // 22 -> 20
        assertEquals(2, report.booksCompletedBucket)
        assertEquals(listOf("gutenberg"), report.sourcesUsed)
    }

    // ── majorMinor ──────────────────────────────────────────────────────

    @Test
    fun `majorMinor keeps only major dot minor and strips build suffixes`() {
        assertEquals("1.9", ImpactReportCalculator.majorMinor("1.9.0"))
        assertEquals("1.9", ImpactReportCalculator.majorMinor("1.9.3-dirty+abc123"))
        assertEquals("2.0", ImpactReportCalculator.majorMinor("2.0"))
        assertEquals("1.0", ImpactReportCalculator.majorMinor("1"))
        assertEquals("0.0", ImpactReportCalculator.majorMinor(""))
    }

    // ── periodLabel ─────────────────────────────────────────────────────

    @Test
    fun `periodLabel is zero-padded year-month in the given zone`() {
        assertEquals("2026-07", ImpactReportCalculator.periodLabel(Instant.parse("2026-07-03T10:00:00Z"), zone))
        assertEquals("2026-01", ImpactReportCalculator.periodLabel(Instant.parse("2026-01-31T23:59:00Z"), zone))
    }

    // ── hasSomethingToShare ─────────────────────────────────────────────

    @Test
    fun `hasSomethingToShare is false only when every field is empty`() {
        val empty = ImpactReportCalculator.buildReport(ImpactTotals.ZERO, ImpactTotals.ZERO, "2026-07", "1.9")
        assertFalse(ImpactReportCalculator.hasSomethingToShare(empty))

        val onlySources = ImpactReportCalculator.buildReport(
            ImpactTotals(0, 0, 0, setOf("gutenberg")), ImpactTotals.ZERO, "2026-07", "1.9",
        )
        assertTrue(ImpactReportCalculator.hasSomethingToShare(onlySources))
    }
}
