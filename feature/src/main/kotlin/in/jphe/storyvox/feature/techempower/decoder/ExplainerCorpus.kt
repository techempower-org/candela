package `in`.jphe.storyvox.feature.techempower.decoder

import kotlinx.serialization.Serializable

/**
 * Issue #1516 — data model for the **benefits letter decoder** corpus.
 *
 * The decoder photographs a Notice of Action → OCR → detects the form number →
 * shows a plain-language, **hand-written verified explainer** (EN/ES) of what
 * that notice type means and what to do. NO generative summarization of the
 * user's actual letter: a wrong paraphrase of a benefits notice is a harm.
 *
 * VERIFIED-OR-SILENT (epic #1520 invariant 3): this module ships the SURFACE +
 * detection + a corpus READER. The bundled `notice_explainers.json` is a SMALL,
 * clearly-marked SEED SAMPLE ([DecoderMetadata.provenance] == `"seed-sample"`).
 * The production corpus (top ~10 CA notice types, EN/ES, each explainer with
 * verifiedAt + source) is TechEmpower-supplied and replaces the asset wholesale.
 * An UNKNOWN form number → honest fallback, never a guess.
 */
@Serializable
data class ExplainerCorpus(
    val metadata: DecoderMetadata,
    val explainers: List<NoticeExplainer> = emptyList(),
) {
    /** Normalized form-number → explainer, including aliases. Built once. */
    private val byNormalizedForm: Map<String, NoticeExplainer> by lazy {
        buildMap {
            for (e in explainers) {
                put(normalizeFormNumber(e.formNumber), e)
                for (alias in e.aliases) put(normalizeFormNumber(alias), e)
            }
        }
    }

    /** Look up a verified explainer for [rawFormNumber]; null when we have none. */
    fun find(rawFormNumber: String): NoticeExplainer? =
        byNormalizedForm[normalizeFormNumber(rawFormNumber)]
}

@Serializable
data class DecoderMetadata(
    val schemaVersion: Int = 1,
    val provenance: String = PROVENANCE_SEED,
    val verifiedDate: String? = null,
    val freshnessMaxAgeDays: Long = 365,
    val source: String? = null,
    val note: String? = null,
) {
    val isVerified: Boolean get() = provenance == PROVENANCE_VERIFIED

    companion object {
        const val PROVENANCE_SEED = "seed-sample"
        const val PROVENANCE_VERIFIED = "techempower-verified"
    }
}

/** A bilingual string; [get] picks the language, falling back to EN. */
@Serializable
data class Localized(
    val en: String,
    val es: String? = null,
) {
    fun get(spanish: Boolean): String = if (spanish) (es ?: en) else en
}

/**
 * One verified explainer for a notice type. All fields are hand-written verified
 * content in the production corpus; the seed carries placeholder text plainly
 * labeled in [source].
 */
@Serializable
data class NoticeExplainer(
    val formNumber: String,
    val aliases: List<String> = emptyList(),
    val title: Localized,
    val whatItMeans: Localized,
    val whyYouGotIt: Localized,
    val whatToDo: Localized,
    val phone: String? = null,
    val verifiedAt: String? = null,
    val source: Localized? = null,
)

/**
 * Canonical form-number key: uppercase, strip everything but letters/digits.
 * "NA 200" / "na-200" / "NA200" all collapse to "NA200" so detection and lookup
 * agree regardless of how the OCR rendered the spaces/dashes.
 */
fun normalizeFormNumber(raw: String): String =
    raw.uppercase().filter { it.isLetterOrDigit() }
