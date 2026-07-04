package `in`.jphe.storyvox.feature.techempower.screener

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Issue #1517 — data model for the **offline benefits screener** corpus.
 *
 * The screener bundles a client-side "Do I qualify?" flow (the same idea as
 * techempower.org/qualify) as an **on-device asset** so it runs with zero
 * connectivity — airplane mode, no data plan, no network of any kind. The
 * privacy promise ("your answers never leave your device") is *provable* here
 * because the surface makes no network calls at all: it only reads a bundled
 * JSON asset and evaluates locally.
 *
 * VERIFIED-OR-SILENT (epic #1520 invariant 3): benefits *content* is
 * TechEmpower-supplied from their adversarial fact-check pipeline. This module
 * ships the SURFACE + a corpus READER; the bundled `screener_corpus.json` is a
 * SMALL, clearly-marked SEED SAMPLE ([CorpusMetadata.provenance] ==
 * `"seed-sample"`). The production corpus (33 verified programs, EN/ES)
 * replaces the asset wholesale; flip [CorpusMetadata.provenance] to
 * `"techempower-verified"` and the seed banner disappears. We never author or
 * paraphrase program facts.
 *
 * All user-facing content carries both languages ([Localized]); UI chrome is in
 * `res/values` + `res/values-es`. The corpus's own verified-date is surfaced in
 * the screener footer (acceptance: "corpus verified-date visible; matches the
 * shipped rules JSON").
 */
@Serializable
data class ScreenerCorpus(
    val metadata: CorpusMetadata,
    val questions: List<ScreenerQuestion> = emptyList(),
    val programs: List<ScreenerProgram> = emptyList(),
)

/**
 * Provenance + freshness metadata for the corpus. [provenance] gates the
 * "sample data" banner: anything other than [PROVENANCE_VERIFIED] is treated as
 * un-verified seed content and the UI says so loudly.
 */
@Serializable
data class CorpusMetadata(
    val schemaVersion: Int = 1,
    val provenance: String = PROVENANCE_SEED,
    val verifiedDate: String? = null,
    val source: String? = null,
    val note: String? = null,
) {
    /** True when this corpus is TechEmpower's verified production data. */
    val isVerified: Boolean get() = provenance == PROVENANCE_VERIFIED

    companion object {
        const val PROVENANCE_SEED = "seed-sample"
        const val PROVENANCE_VERIFIED = "techempower-verified"
    }
}

/** A bilingual string. [get] picks the language, falling back to EN. */
@Serializable
data class Localized(
    val en: String,
    val es: String? = null,
) {
    fun get(spanish: Boolean): String = if (spanish) (es ?: en) else en
}

/** The shape of an answer a [ScreenerQuestion] collects. */
enum class QuestionType { BOOLEAN, SINGLE_SELECT }

@Serializable
data class ScreenerQuestion(
    val id: String,
    val type: String,
    val prompt: Localized,
    val options: List<ScreenerOption> = emptyList(),
) {
    val questionType: QuestionType
        get() = when (type) {
            "single_select" -> QuestionType.SINGLE_SELECT
            else -> QuestionType.BOOLEAN
        }
}

@Serializable
data class ScreenerOption(
    val id: String,
    val label: Localized,
)

/**
 * A benefits program the screener can surface. [criteria] is evaluated locally
 * against the user's answers (see [ScreenerEligibility]). An empty criteria list
 * means "always relevant" (e.g. the 211 help line).
 *
 * [phone] is null unless a number is *verified* (only 211 in the seed). We never
 * invent a phone number; unknown numbers route the user to 211 / the apply URL.
 */
@Serializable
data class ScreenerProgram(
    val id: String,
    val org: String? = null,
    val category: String? = null,
    val name: Localized,
    val summary: Localized,
    val phone: String? = null,
    val applyUrl: String? = null,
    val verifiedDate: String? = null,
    val sourceNote: Localized? = null,
    val criteria: List<Criterion> = emptyList(),
)

/**
 * One eligibility test referencing a [ScreenerQuestion] by [questionId].
 * [op] is one of [Criterion.OP_IS_TRUE] / [Criterion.OP_IS_FALSE] /
 * [Criterion.OP_EQUALS]; [value] is the option id for `equals`.
 */
@Serializable
data class Criterion(
    val questionId: String,
    val op: String,
    @SerialName("value") val value: String? = null,
) {
    companion object {
        const val OP_IS_TRUE = "is_true"
        const val OP_IS_FALSE = "is_false"
        const val OP_EQUALS = "equals"
    }
}
