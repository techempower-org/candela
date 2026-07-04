package `in`.jphe.storyvox.data.repository.impact

import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import java.time.Instant
import java.time.ZoneId
import javax.inject.Qualifier
import kotlin.math.roundToLong

/**
 * Issue #1463 — opt-in **anonymous impact sharing** (user-triggered share/export).
 *
 * Per the decided design (`docs/superpowers/specs/2026-07-01-impact-metrics-design.md`
 * §0.5): there is **no server, no automatic transmission, no consent toggle**. The
 * feature is a user-initiated share — the user taps "Share impact report" on the
 * stats screen, previews the *exact* coarse payload, and sends it through the Android
 * share sheet to a destination of their choosing. TechEmpower tallies whatever arrives.
 *
 * Everything in this file is **pure** (no Android, no Room, no clock) so it unit-tests
 * against fixed inputs — the anonymization math is the load-bearing part and must be
 * provably correct.
 */

/**
 * The **coarsened cumulative** impact totals. This is both the persisted "last shared"
 * baseline (see [ImpactShareStore]) and the building block of a delta payload.
 *
 * - [hoursListened] / [chaptersCompleted] are snapped to a coarse bucket (§4.2) so no
 *   single figure is precise enough to single out a device.
 * - [booksCompleted] is already coarse (bucket of 1) and stored as-is.
 * - [sourceIds] is the *set* of built-in source IDs that have ≥1 finished chapter — a
 *   presence set, never per-source counts (§0.5 Q5).
 */
data class ImpactTotals(
    /** Coarsened cumulative estimated hours listened (multiple of [ImpactReportCalculator.HOURS_BUCKET]). */
    val hoursListened: Int,
    /** Coarsened cumulative finished-chapter count (multiple of [ImpactReportCalculator.CHAPTERS_BUCKET]). */
    val chaptersCompleted: Int,
    /** Cumulative finished-book count (bucket of 1 — already coarse). */
    val booksCompleted: Int,
    /** Set of source IDs with ≥1 finished chapter. Presence only, never counts. */
    val sourceIds: Set<String>,
) {
    companion object {
        val ZERO = ImpactTotals(
            hoursListened = 0,
            chaptersCompleted = 0,
            booksCompleted = 0,
            sourceIds = emptySet(),
        )
    }
}

/**
 * The exact payload the user shares this period — **coarse deltas since the last share**
 * (§4.1). Deltas (not lifetime totals) make the org-wide tally a plain SUM and mean any
 * single shared report reveals only one month of one device, never a lifetime fingerprint.
 */
data class ImpactReport(
    /** Payload schema version (§4.1). */
    val schema: Int,
    /** Reporting period, month granularity only: `"YYYY-MM"`. Never day/time. */
    val period: String,
    /** App version, `major.minor` only — a build hash can't narrow the population. */
    val appVersion: String,
    /** Δ estimated hours listened since last share, coarse, clamped ≥ 0. */
    val hoursListenedBucket: Int,
    /** Δ finished chapters since last share, coarse, clamped ≥ 0. */
    val chaptersCompletedBucket: Int,
    /** Δ finished books since last share, clamped ≥ 0. */
    val booksCompletedBucket: Int,
    /** Source IDs newly used since last share (this-period set). Sorted, stable. */
    val sourcesUsed: List<String>,
)

/**
 * Pure coarsening + delta math for [ImpactReport]. The clock/zone and app version are
 * passed in, so the unit tests pin them and assert exact buckets.
 */
object ImpactReportCalculator {

    /** Payload schema version. Bump if the field shape changes (re-triggers §8.3 audit). */
    const val SCHEMA_VERSION: Int = 1

    /** Hours are snapped to the nearest multiple of this (§4.2). */
    const val HOURS_BUCKET: Int = 5

    /** Finished chapters are snapped to the nearest multiple of this (§4.2). */
    const val CHAPTERS_BUCKET: Int = 5

    /** Books are already coarse — a bucket of 1 is the identity, kept for symmetry. */
    const val BOOKS_BUCKET: Int = 1

    private const val MILLIS_PER_HOUR: Double = 3_600_000.0

    /**
     * Snap a non-negative [value] to the nearest multiple of [bucket] (ties round up).
     * A [bucket] ≤ 1 is the identity (values pass through unchanged). Negative inputs
     * clamp to 0 — the caller never has a legitimate negative cumulative total.
     */
    fun coarsen(value: Int, bucket: Int): Int {
        if (value <= 0) return 0
        if (bucket <= 1) return value
        // (value + bucket/2) / bucket * bucket, integer math, half-up.
        return ((value + bucket / 2) / bucket) * bucket
    }

    /**
     * Coarsen an estimated-milliseconds figure into whole hours snapped to
     * [HOURS_BUCKET]. Rounds ms→hours to the nearest whole hour first, then buckets,
     * so "just under 5h" and "just over 5h" don't both collapse to 0 spuriously.
     */
    fun coarsenHours(estimatedMs: Long): Int {
        if (estimatedMs <= 0L) return 0
        val wholeHours = (estimatedMs / MILLIS_PER_HOUR).roundToLong()
        if (wholeHours <= 0L) return 0
        return coarsen(wholeHours.toInt(), HOURS_BUCKET)
    }

    /**
     * Coarsen a raw [ListeningStats] snapshot into cumulative [ImpactTotals]. The source
     * set is every source with a positive finished-chapter count (presence only).
     */
    fun coarsenSnapshot(stats: ListeningStats): ImpactTotals = ImpactTotals(
        hoursListened = coarsenHours(stats.totalEstimatedMs),
        chaptersCompleted = coarsen(stats.chaptersFinished, CHAPTERS_BUCKET),
        booksCompleted = coarsen(stats.booksCompleted, BOOKS_BUCKET),
        sourceIds = stats.perSource
            .filter { it.finishedChapters > 0 }
            .map { it.sourceId }
            .toSet(),
    )

    /**
     * Reduce a full [versionName] (e.g. `"1.9.0"`, `"1.9.0-dirty+abc123"`) to
     * `major.minor` (`"1.9"`). Only leading digits of each part are kept, so build
     * suffixes never leak. A blank or malformed name degrades to `"0.0"`.
     */
    fun majorMinor(versionName: String): String {
        val parts = versionName.trim().split('.')
        val major = parts.getOrNull(0)?.takeWhile { it.isDigit() }?.ifEmpty { null } ?: "0"
        val minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.ifEmpty { null } ?: "0"
        return "$major.$minor"
    }

    /** The reporting-period label for [now] in [zone]: `"YYYY-MM"`, zero-padded. */
    fun periodLabel(now: Instant, zone: ZoneId): String {
        val zdt = now.atZone(zone)
        val year = zdt.year
        val month = zdt.monthValue
        val mm = if (month < 10) "0$month" else "$month"
        return "$year-$mm"
    }

    /**
     * Build the coarse delta payload.
     *
     * `Δ = coarsen(current cumulative) − lastShared` (§4.4), clamped at 0 per field so a
     * cleared/reinstalled data set (current < lastShared) never produces negative deltas.
     * Because both operands are coarsened cumulative totals, each delta is naturally a
     * multiple of the bucket; the quantization error averages out at org scale.
     *
     * `sourcesUsed` is the this-period set: sources present now that weren't in the
     * last-shared set. Unioning these across every period recovers the full breadth.
     *
     * @param current coarsened totals from the live snapshot ([coarsenSnapshot])
     * @param lastShared the coarsened totals persisted at the previous share ([ImpactTotals.ZERO] if never shared)
     */
    fun buildReport(
        current: ImpactTotals,
        lastShared: ImpactTotals,
        period: String,
        appVersion: String,
    ): ImpactReport = ImpactReport(
        schema = SCHEMA_VERSION,
        period = period,
        appVersion = appVersion,
        hoursListenedBucket = (current.hoursListened - lastShared.hoursListened).coerceAtLeast(0),
        chaptersCompletedBucket = (current.chaptersCompleted - lastShared.chaptersCompleted).coerceAtLeast(0),
        booksCompletedBucket = (current.booksCompleted - lastShared.booksCompleted).coerceAtLeast(0),
        sourcesUsed = (current.sourceIds - lastShared.sourceIds).sorted(),
    )

    /** True when a report carries at least one non-zero contribution worth sharing. */
    fun hasSomethingToShare(report: ImpactReport): Boolean =
        report.hoursListenedBucket > 0 ||
            report.chaptersCompletedBucket > 0 ||
            report.booksCompletedBucket > 0 ||
            report.sourcesUsed.isNotEmpty()
}

/**
 * Hilt qualifier for the app's `major.minor.patch` version name (`BuildConfig.VERSION_NAME`).
 * Provided by the `:app` graph; consumed by [ImpactReporter], which reduces it to
 * `major.minor` for the payload.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImpactAppVersion
