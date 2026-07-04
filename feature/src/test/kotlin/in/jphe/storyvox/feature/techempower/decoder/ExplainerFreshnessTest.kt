package `in`.jphe.storyvox.feature.techempower.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Issue #1516 — pins the freshness gate logic (acceptance: "a CI check fails
 * explainers older than the freshness bar"). Deterministic: `asOf` is supplied,
 * never read from the wall clock.
 */
class ExplainerFreshnessTest {

    private val asOf = LocalDate.of(2026, 7, 4)

    @Test
    fun freshWhenWithinMaxAge() {
        assertTrue(ExplainerFreshness.isFresh("2026-06-01", asOf, maxAgeDays = 365))
    }

    @Test
    fun staleWhenBeyondMaxAge() {
        assertFalse(ExplainerFreshness.isFresh("2024-01-01", asOf, maxAgeDays = 365))
    }

    @Test
    fun exactlyAtBarIsFresh() {
        // 365 days before asOf, maxAge 365 → still fresh (<=).
        assertTrue(ExplainerFreshness.isFresh("2025-07-04", asOf, maxAgeDays = 365))
    }

    @Test
    fun oneDayPastBarIsStale() {
        assertFalse(ExplainerFreshness.isFresh("2025-07-03", asOf, maxAgeDays = 365))
    }

    @Test
    fun futureDateTreatedAsFresh() {
        assertTrue(ExplainerFreshness.isFresh("2027-01-01", asOf, maxAgeDays = 365))
    }

    @Test
    fun nullOrUnparseableIsNotFresh() {
        assertFalse(ExplainerFreshness.isFresh(null, asOf, maxAgeDays = 365))
        assertFalse(ExplainerFreshness.isFresh("", asOf, maxAgeDays = 365))
        assertFalse(ExplainerFreshness.isFresh("not-a-date", asOf, maxAgeDays = 365))
    }

    @Test
    fun staleExplainersFiltersCorrectly() {
        fun e(form: String, at: String?) = NoticeExplainer(
            formNumber = form,
            title = Localized("t"), whatItMeans = Localized("m"),
            whyYouGotIt = Localized("w"), whatToDo = Localized("d"),
            verifiedAt = at,
        )
        val corpus = ExplainerCorpus(
            metadata = DecoderMetadata(freshnessMaxAgeDays = 365),
            explainers = listOf(
                e("FRESH", "2026-06-01"),
                e("STALE", "2020-01-01"),
                e("MISSING", null),
            ),
        )
        val stale = ExplainerFreshness.staleExplainers(corpus, asOf).map { it.formNumber }.toSet()
        assertEquals(setOf("STALE", "MISSING"), stale)
    }
}
