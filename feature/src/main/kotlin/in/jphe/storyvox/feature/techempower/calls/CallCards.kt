package `in`.jphe.storyvox.feature.techempower.calls

import kotlinx.serialization.Serializable

/**
 * Issue #1518 — data model for the **"make the call"** corpus.
 *
 * Per-program call cards: the number (tap-to-dial), best time to call, a short
 * script of what to say, a checklist of what to ask, and a capture form for the
 * answers. Cards are listenable (rehearse the call in the car).
 *
 * VERIFIED-OR-SILENT (epic #1520 invariant 3): ships the SURFACE + a corpus
 * READER. The bundled `call_cards.json` is a SMALL, clearly-marked SEED SAMPLE
 * ([CallCardsMetadata.provenance] == `"seed-sample"`); scripts/checklists are
 * generic call-coaching placeholders. The only asserted number is the public
 * 211 line — the direct program lines come from the TechEmpower-supplied corpus
 * + SEASON-OPS phone book (each carrying verifiedAt). A card with no verified
 * [CallCard.phone] routes the caller through 211. We never invent a number.
 */
@Serializable
data class CallCardsCorpus(
    val metadata: CallCardsMetadata,
    val cards: List<CallCard> = emptyList(),
)

@Serializable
data class CallCardsMetadata(
    val schemaVersion: Int = 1,
    val provenance: String = PROVENANCE_SEED,
    val verifiedDate: String? = null,
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

@Serializable
data class CallCard(
    val id: String,
    val org: String? = null,
    val title: Localized,
    val bestTimeToCall: Localized? = null,
    /** Verified direct number, or null → route through 211 (never invented). */
    val phone: String? = null,
    val whatToSay: List<Localized> = emptyList(),
    val whatToAsk: List<Localized> = emptyList(),
    val captureFields: List<CaptureField> = emptyList(),
    val verifiedDate: String? = null,
    val source: Localized? = null,
) {
    /** The number the dial affordance uses: the verified line, or 211. */
    fun dialNumber(fallback: String = "211"): String = phone ?: fallback
}

/** One field in a card's post-call answer-capture form. */
@Serializable
data class CaptureField(
    val id: String,
    val label: Localized,
)
