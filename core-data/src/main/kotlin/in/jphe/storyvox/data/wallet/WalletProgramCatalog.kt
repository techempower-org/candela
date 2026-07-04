package `in`.jphe.storyvox.data.wallet

/**
 * Issue #1514 — the "program-aware" part of the wallet: which stored
 * proof types are accepted by which benefits programs ("You stored a
 * Medi-Cal card — that's income proof for LIHEAP…").
 *
 * ## Verified-or-silent (epic #1520, invariant #3)
 *
 * These proof→program mappings are **benefits facts**, so they are NOT
 * authored from nothing. This object is a SMALL, clearly-marked
 * [SEED_VERIFIED_DATE]-stamped **SAMPLE**; the production corpus is
 * TechEmpower-supplied from the same adversarial fact-check pipeline as
 * techempower.org/qualify and the offline screener (#1517). A document
 * type with no seed entry returns an EMPTY list — the UI then says
 * honestly "we don't have a verified list yet", never a guess.
 *
 * Program ids mirror the screener's canonical `ScreenerProgramIds`
 * namespace (#1517) as local constants ([ProgramIds]) so the two align
 * once that corpus merges — see the cross-lane storage/screener seam in
 * the wave brief.
 */
object WalletProgramCatalog {

    /**
     * SAMPLE data — replace with the TechEmpower-verified corpus before
     * production. Stamped so the freshness bar can age it.
     */
    const val SEED_VERIFIED_DATE = "2026-07-04"

    /** True when the mapping is still the built-in seed (the UI surfaces
     *  a "sample data" note; production overrides this). */
    const val IS_SEED = true

    /**
     * Canonical program ids, mirroring the screener corpus (#1517)
     * `ScreenerProgramIds`. Local constants so #1514 does not hard-depend
     * on the unmerged screener types; string VALUES must match.
     */
    object ProgramIds {
        const val LIHEAP = "LIHEAP"
        const val NID_WATER_DISCOUNT = "NID_WATER_DISCOUNT"
        const val FREED_BATTERY_BACKUP = "FREED_BATTERY_BACKUP"
        const val HELP_211 = "HELP_211"
    }

    /** A program that accepts a given proof. [displayName] is a plain
     *  label for the "What does this prove?" list. */
    data class AcceptingProgram(val id: String, val displayName: String)

    // SAMPLE mappings only. Deliberately conservative + few. Empty for
    // types we have no verified mapping for (→ honest "no list yet").
    private val seed: Map<WalletDocType, List<AcceptingProgram>> = mapOf(
        WalletDocType.PROOF_OF_INCOME to listOf(
            AcceptingProgram(ProgramIds.LIHEAP, "LIHEAP (energy bill help)"),
            AcceptingProgram(ProgramIds.NID_WATER_DISCOUNT, "NID water discount"),
        ),
        WalletDocType.BENEFIT_CARD to listOf(
            AcceptingProgram(ProgramIds.LIHEAP, "LIHEAP (energy bill help)"),
            AcceptingProgram(ProgramIds.NID_WATER_DISCOUNT, "NID water discount"),
        ),
        WalletDocType.AWARD_LETTER to listOf(
            AcceptingProgram(ProgramIds.LIHEAP, "LIHEAP (energy bill help)"),
            AcceptingProgram(ProgramIds.HELP_211, "2-1-1 resource help"),
        ),
    )

    /**
     * Programs that accept [type] as proof. Empty list = no verified
     * mapping yet (the UI must say so, not guess).
     */
    fun programsAccepting(type: WalletDocType): List<AcceptingProgram> = seed[type].orEmpty()
}
