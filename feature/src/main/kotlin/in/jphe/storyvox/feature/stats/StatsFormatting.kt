package `in`.jphe.storyvox.feature.stats

import `in`.jphe.storyvox.data.repository.stats.DayPart
import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import `in`.jphe.storyvox.data.source.SourceIds
import kotlin.math.round

/**
 * Issue #1235 — pure presentation helpers for the listening-stats
 * dashboard. Kept free of Android/Compose imports so they're unit-
 * tested directly in `:feature`'s JVM test source set (the module ships
 * no Compose-test infrastructure — see SettingsSubscreenContractTest).
 */
object StatsFormatting {

    /**
     * Human duration: "12h 30m", "45m", "<1m" (for a non-zero sub-minute
     * span), or "0m". The "≈" estimate marker is added by the caller, not
     * baked in here, so the same formatter serves both estimated and
     * exact figures.
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = ms / 60_000L
        if (totalMinutes <= 0L) return "<1m"
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours <= 0L -> "${minutes}m"
            minutes == 0L -> "${hours}h"
            else -> "${hours}h ${minutes}m"
        }
    }

    /**
     * Compact count: exact below 1,000, then "12.3k" / "1.2M" with a
     * single decimal that drops a trailing ".0". Built arithmetically
     * (no `String.format`) so it's locale-stable and deterministic in
     * tests.
     */
    fun formatCompactNumber(n: Long): String = when {
        n < 0L -> "0"
        n < 1_000L -> n.toString()
        n < 1_000_000L -> trimOneDecimal(n / 1_000.0) + "k"
        else -> trimOneDecimal(n / 1_000_000.0) + "M"
    }

    private fun trimOneDecimal(value: Double): String {
        val rounded = round(value * 10.0) / 10.0
        val whole = rounded.toLong()
        val frac = round((rounded - whole) * 10.0).toLong()
        return if (frac == 0L) whole.toString() else "$whole.$frac"
    }

    /**
     * Human label for a source id. Covers the full [SourceIds] roster;
     * an unknown id (a future source, or a legacy alias) falls back to a
     * title-cased version of the id so the row is never blank.
     */
    fun sourceDisplayName(sourceId: String): String = when (sourceId) {
        SourceIds.ROYAL_ROAD -> "Royal Road"
        SourceIds.GITHUB -> "GitHub"
        SourceIds.MEMPALACE -> "Memory Palace"
        SourceIds.RSS -> "RSS"
        SourceIds.EPUB -> "EPUB"
        SourceIds.OCR -> "Scanned text"
        SourceIds.PDF -> "PDF"
        SourceIds.OUTLINE -> "Outline"
        SourceIds.GUTENBERG -> "Project Gutenberg"
        SourceIds.AO3 -> "AO3"
        SourceIds.STANDARD_EBOOKS -> "Standard Ebooks"
        SourceIds.WIKIPEDIA -> "Wikipedia"
        SourceIds.WIKISOURCE -> "Wikisource"
        SourceIds.RADIO, SourceIds.KVMR -> "Radio"
        SourceIds.LIBRIVOX -> "LibriVox"
        SourceIds.NOTION, SourceIds.NOTION_PAT -> "Notion"
        SourceIds.NOTION_TECHEMPOWER -> "TechEmpower"
        SourceIds.HACKERNEWS -> "Hacker News"
        SourceIds.ARXIV -> "arXiv"
        SourceIds.PLOS -> "PLOS"
        SourceIds.DISCORD -> "Discord"
        SourceIds.MATRIX -> "Matrix"
        SourceIds.TELEGRAM -> "Telegram"
        SourceIds.SLACK -> "Slack"
        SourceIds.PALACE -> "Palace Project"
        SourceIds.READABILITY -> "Web articles"
        SourceIds.MY_AUDIOBOOK -> "My audiobooks"
        else -> prettifyUnknownId(sourceId)
    }

    private fun prettifyUnknownId(sourceId: String): String {
        if (sourceId.isBlank()) return "Other"
        return sourceId
            .split('-', '_', ':')
            .filter { it.isNotBlank() }
            .joinToString(" ") { part ->
                part.replaceFirstChar { it.uppercaseChar() }
            }
            .ifBlank { "Other" }
    }

    /**
     * A short, shareable plain-text summary of the snapshot — backs the
     * top-bar "share" action (the issue's "I listened to N hours" card,
     * as text rather than a rendered image). Lines are emitted only when
     * they carry a non-zero figure, so a sparse history reads cleanly.
     */
    fun shareSummary(stats: ListeningStats, appName: String): String = buildString {
        append("My listening on ")
        append(appName)
        append(':')
        if (stats.totalEstimatedMs > 0L) {
            append("\n• ≈ ")
            append(formatDuration(stats.totalEstimatedMs))
            append(" listened")
        }
        if (stats.booksCompleted > 0) {
            append("\n• ")
            append(stats.booksCompleted)
            append(if (stats.booksCompleted == 1) " book finished" else " books finished")
        }
        if (stats.chaptersFinished > 0) {
            append("\n• ")
            append(formatCompactNumber(stats.chaptersFinished.toLong()))
            append(" chapters read")
        }
        if (stats.currentStreakDays > 0) {
            append("\n• ")
            append(stats.currentStreakDays)
            append(if (stats.currentStreakDays == 1) " day streak" else " day streak 🔥")
        }
    }

    /** Single-letter weekday initial (Mon→"M") for the compact trend axis. */
    fun weekdayInitial(dayOfWeekValue: Int): String = when (dayOfWeekValue) {
        1 -> "M"
        2 -> "T"
        3 -> "W"
        4 -> "T"
        5 -> "F"
        6 -> "S"
        else -> "S"
    }

    /** Display label for a [DayPart]. */
    fun dayPartLabel(part: DayPart): String = when (part) {
        DayPart.MORNING -> "Morning"
        DayPart.AFTERNOON -> "Afternoon"
        DayPart.EVENING -> "Evening"
        DayPart.NIGHT -> "Night"
    }
}
