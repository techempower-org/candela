package `in`.jphe.storyvox.feature.techempower.screener

/**
 * Issue #1517 — canonical benefits **program ids**, owned by the screener lane.
 *
 * WHY THIS FILE EXISTS (cross-lane seam, epic #1520): other benefits features
 * are "program-aware" and key off these ids —
 *  - the wallet's proof-slots (#1514) map documents to the programs that need
 *    them,
 *  - the household-profile autofill (#1519) pre-fills applications per program.
 *
 * The screener owns the corpus, so it owns the id namespace. Consumers should
 * depend on these plain string constants (a merged type is not required to
 * cross a lane boundary), and the ids MUST match the `"id"` values in
 * `assets/techempower/screener_corpus.json`. When the production corpus lands,
 * keep these constants in sync with its ids (a corpus program whose id is not a
 * constant here still works — this is a convenience surface for other lanes,
 * not a gate).
 *
 * Ids are stable, lowercase, snake_case, and never re-used for a different
 * program once shipped.
 */
object ScreenerProgramIds {
    /** LIHEAP energy-bill assistance (locally: Project GO). */
    const val LIHEAP = "liheap"

    /** Nevada Irrigation District reduced water rate. */
    const val NID_WATER_DISCOUNT = "nid_water_discount"

    /** FREED medical battery-backup program. */
    const val FREED_BATTERY_BACKUP = "freed_battery_backup"

    /** United Way 211 local referral line. */
    const val HELP_211 = "help_211"

    /** All ids known to this build's seed corpus. Not exhaustive of production. */
    val seedIds: List<String> = listOf(
        LIHEAP,
        NID_WATER_DISCOUNT,
        FREED_BATTERY_BACKUP,
        HELP_211,
    )
}
