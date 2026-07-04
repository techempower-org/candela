package `in`.jphe.storyvox.data.repository.impact

import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1463 — composes the pure coarsening math ([ImpactReportCalculator]) with the
 * persisted baseline ([ImpactShareStore]) into a ready-to-share payload.
 *
 * Takes an **already-loaded** [ListeningStats] snapshot rather than re-querying the DB —
 * the stats screen already loads one for the dashboard, so it passes it straight in and
 * we never hit Room twice for the same screen open.
 */
@Singleton
class ImpactReporter @Inject constructor(
    private val store: ImpactShareStore,
    @ImpactAppVersion private val appVersionName: String,
) {

    /**
     * Compute the current share payload for [stats] as of [now]/[zone]. Reads the
     * baseline, coarsens the snapshot, and diffs — all cheap, no network.
     */
    suspend fun shareFor(
        stats: ListeningStats,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ImpactShareData {
        val baseline = store.state()
        val current = ImpactReportCalculator.coarsenSnapshot(stats)
        val period = ImpactReportCalculator.periodLabel(now, zone)
        val report = ImpactReportCalculator.buildReport(
            current = current,
            lastShared = baseline.totals,
            period = period,
            appVersion = ImpactReportCalculator.majorMinor(appVersionName),
        )
        return ImpactShareData(
            report = report,
            currentTotals = current,
            lastSharedPeriod = baseline.lastSharedPeriod,
            payloadText = ImpactReportJson.encode(report),
            hasSomethingToShare = ImpactReportCalculator.hasSomethingToShare(report),
        )
    }

    /**
     * Persist a completed share: [ImpactShareData.currentTotals] becomes the new
     * baseline for [ImpactShareData.report]'s period. After this, re-computing
     * [shareFor] with the same snapshot yields a zero delta (nothing left to share
     * this period) and a matching `lastSharedPeriod`.
     */
    suspend fun markShared(data: ImpactShareData) {
        store.recordShared(data.currentTotals, data.report.period)
    }
}

/**
 * A fully-resolved impact share: the [report], its exact [payloadText] (what the preview
 * shows and the share sheet sends — byte-for-byte identical), the [currentTotals] to
 * persist on share, and UI hints ([lastSharedPeriod], [hasSomethingToShare]).
 */
data class ImpactShareData(
    val report: ImpactReport,
    val currentTotals: ImpactTotals,
    val lastSharedPeriod: String?,
    val payloadText: String,
    val hasSomethingToShare: Boolean,
)
