package `in`.jphe.storyvox.data.repository.impact

/**
 * Issue #1463 — device-local bookkeeping for opt-in impact sharing.
 *
 * Persists the coarsened cumulative totals as of the **last share** plus the period
 * they were shared for, so the next share reports only the incremental delta
 * (`Δ = coarsen(current) − lastShared`). Holds **no identity** — no email, no device ID,
 * no nonce; it exists purely to keep deltas non-double-counting and can be wiped with
 * app data with zero privacy consequence. Not synced (a device-local record).
 *
 * The act of sharing is the consent (§0.5): there is no opt-in flag here.
 */
interface ImpactShareStore {

    /** Read the current baseline. [ImpactShareState.EMPTY] until the user shares once. */
    suspend fun state(): ImpactShareState

    /**
     * Record a completed share: persist [totals] (the coarsened cumulative snapshot that
     * was just shared) as the new baseline, and [period] as the last-shared month. The
     * next [state] read returns these, so a re-share this period reports a zero delta.
     */
    suspend fun recordShared(totals: ImpactTotals, period: String)
}

/**
 * The persisted impact-share baseline.
 *
 * @param totals coarsened cumulative totals as of the last share ([ImpactTotals.ZERO] if never shared)
 * @param lastSharedPeriod the `"YYYY-MM"` of the last share, or null if never shared
 */
data class ImpactShareState(
    val totals: ImpactTotals,
    val lastSharedPeriod: String?,
) {
    companion object {
        val EMPTY = ImpactShareState(totals = ImpactTotals.ZERO, lastSharedPeriod = null)
    }
}
