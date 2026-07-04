package `in`.jphe.storyvox.feature.techempower.deadline

import java.time.LocalDate

/**
 * Issue #1515 — program-aware constants + the recurring-deadline preset
 * corpus.
 *
 * ## Program ids (cross-lane seam)
 *
 * These are plain local string constants on purpose. The wallet (#1514)
 * and the screener corpus (#1517) are also "program-aware" and key off
 * the same ids, but this lane must not hard-depend on another lane's
 * unmerged types — so we define the ids here and the corpus lanes align
 * to them. Keep them lowercase, stable, and boring.
 */
object DeadlinePrograms {
    const val LIFELINE = "lifeline"
    const val CALFRESH = "calfresh"
    const val MEDI_CAL = "medi_cal"
    const val SUN_BUCKS = "sun_bucks"
    const val LIHEAP = "liheap"

    /** Free-form letter with no known program. */
    const val OTHER = "other"
}

/**
 * A known recurring benefits deadline the user can pre-fill instead of
 * scanning a letter (per the issue: "a picker of known recurring clocks
 * — LifeLine annual, SAR-7, Medi-Cal renewal — with plain-language
 * blurbs from the verified corpus").
 *
 * @param programId one of [DeadlinePrograms].
 * @param fixedDeadline a concrete public apply-by date when the program
 *   has one (SUN Bucks 2026-08-31), else null.
 * @param defaultHorizonDays for recurring clocks with no single public
 *   date, how many days out to *suggest* the reminder (the user always
 *   confirms against their own notice). Null when [fixedDeadline] is set.
 * @param verifiedDate freshness stamp — when this entry was last checked.
 */
data class DeadlineRecertPreset(
    val programId: String,
    val fixedDeadline: LocalDate?,
    val defaultHorizonDays: Int?,
    val verifiedDate: LocalDate,
) {
    /** Suggested deadline for a preset, given "today". User confirms/edits. */
    fun suggestedDeadline(today: LocalDate): LocalDate =
        fixedDeadline ?: today.plusDays((defaultHorizonDays ?: 365).toLong())
}

/**
 * ⚠️ SEED SAMPLE — NOT the production corpus.
 *
 * Per invariant #3 ("verified or silent"), benefits *content* — the exact
 * recurrence cadences and the plain-language blurbs — is TechEmpower-
 * supplied from their adversarial fact-check pipeline. This is a small,
 * clearly-labelled seed drawn from the programs named in issue #1515
 * (LifeLine annual re-check, CalFresh SAR-7, Medi-Cal renewal, SUN Bucks
 * apply-by Aug 31 2026, LIHEAP first-come window) so the picker + the
 * whole scheduling path can be built and demoed. The horizons are
 * deliberately soft ("~annual / ~semi-annual"): the UI tells the user to
 * confirm the exact date printed on their own notice, and the app never
 * fires on a preset date the user hasn't confirmed.
 *
 * PRODUCTION: replace [SEED] with the TechEmpower-maintained corpus
 * (JSON asset, per-entry `verified_date`, freshness bar). Tracked as the
 * follow-up flagged in the PR body.
 */
object DeadlineRecertPresets {

    /** Stamp used for every seed row; swap for per-entry dates in production. */
    private val SEED_VERIFIED = LocalDate.of(2026, 7, 4)

    val SEED: List<DeadlineRecertPreset> = listOf(
        DeadlineRecertPreset(
            programId = DeadlinePrograms.LIFELINE,
            fixedDeadline = null,
            defaultHorizonDays = 365, // annual re-certification
            verifiedDate = SEED_VERIFIED,
        ),
        DeadlineRecertPreset(
            programId = DeadlinePrograms.CALFRESH,
            fixedDeadline = null,
            defaultHorizonDays = 180, // SAR-7 semi-annual report
            verifiedDate = SEED_VERIFIED,
        ),
        DeadlineRecertPreset(
            programId = DeadlinePrograms.MEDI_CAL,
            fixedDeadline = null,
            defaultHorizonDays = 365, // annual renewal
            verifiedDate = SEED_VERIFIED,
        ),
        DeadlineRecertPreset(
            programId = DeadlinePrograms.SUN_BUCKS,
            fixedDeadline = LocalDate.of(2026, 8, 31), // public apply-by, per #1515
            defaultHorizonDays = null,
            verifiedDate = SEED_VERIFIED,
        ),
    )
}
