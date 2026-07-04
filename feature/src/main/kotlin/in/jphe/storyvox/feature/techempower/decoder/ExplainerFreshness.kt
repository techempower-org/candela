package `in`.jphe.storyvox.feature.techempower.decoder

import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Issue #1516 — pure freshness check for verified explainers.
 *
 * Acceptance criterion: "a CI check fails explainers older than the freshness
 * bar." This is the logic behind that gate — deterministic (caller supplies
 * `asOf`, no wall-clock here). The time-relative CI gate is enabled against the
 * TechEmpower-supplied production corpus; the seed sample intentionally does not
 * fail CI as it ages (it is placeholder content, not verified).
 */
object ExplainerFreshness {

    /** True when [verifiedAt] (ISO yyyy-MM-dd) is within [maxAgeDays] of [asOf]. */
    fun isFresh(verifiedAt: String?, asOf: LocalDate, maxAgeDays: Long): Boolean {
        val date = parse(verifiedAt) ?: return false // unparseable / missing → not fresh
        if (date.isAfter(asOf)) return true // stamped in the future → treat as fresh
        val ageDays = ChronoUnit.DAYS.between(date, asOf)
        return ageDays <= maxAgeDays
    }

    /** Explainers in [corpus] that are stale as of [asOf], by the corpus's bar. */
    fun staleExplainers(corpus: ExplainerCorpus, asOf: LocalDate): List<NoticeExplainer> =
        corpus.explainers.filter { !isFresh(it.verifiedAt, asOf, corpus.metadata.freshnessMaxAgeDays) }

    private fun parse(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDate.parse(value)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
