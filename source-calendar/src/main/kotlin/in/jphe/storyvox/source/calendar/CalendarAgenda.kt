package `in`.jphe.storyvox.source.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure agenda logic for the device-calendar source (#1495): bucket a flat list
 * of [CalendarEvent] instances into the Today / Tomorrow / This Week chapters
 * and render each chapter's narration text.
 *
 * Deliberately Android-free and clock-free — every entry point takes `nowMillis`
 * + `zone` explicitly — so it's exhaustively unit-testable with fixed inputs
 * (see `CalendarAgendaTest`). [CalendarSource] is the only thing that supplies
 * the real wall clock / device zone.
 *
 * Reading model (from the issue): "My Calendar" is one fiction; these three
 * chapters are its body. All-day events are summarised first in each chapter,
 * then timed events in chronological order — time, title, location, duration.
 */
object CalendarAgenda {

    /** How many days past *tomorrow* the "This Week" chapter looks ahead. The
     *  query window is [today 00:00, today 00:00 + (2 + [WEEK_TAIL_DAYS]) days). */
    private const val WEEK_TAIL_DAYS = 5L

    /** Stable per-chapter discriminator. */
    enum class Bucket(val key: String, val title: String) {
        TODAY("today", "Today"),
        TOMORROW("tomorrow", "Tomorrow"),
        WEEK("week", "This Week"),
    }

    /** A rendered chapter: display [title], narration [plain] text (for TTS),
     *  and [html] (for the reader view). [eventCount] drives the fiction summary. */
    data class Chapter(
        val bucket: Bucket,
        val title: String,
        val plain: String,
        val html: String,
        val eventCount: Int,
    )

    /**
     * The single ContentResolver query window that covers all three chapters:
     * from the start of today (device zone) through the end of the look-ahead
     * week, as epoch-UTC millis. One query, then bucket in memory.
     */
    fun queryWindowUtcMillis(nowMillis: Long, zone: ZoneId): LongRange {
        val today = today(nowMillis, zone)
        val begin = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(2 + WEEK_TAIL_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
        return begin until end
    }

    /** Build all three chapters. Always returns exactly three, in reading order,
     *  even when empty — the fiction's shape is stable day to day. */
    fun chapters(events: List<CalendarEvent>, nowMillis: Long, zone: ZoneId): List<Chapter> {
        val today = today(nowMillis, zone)
        val tomorrow = today.plusDays(1)
        val weekStart = today.plusDays(2)
        val weekEndExclusive = today.plusDays(2 + WEEK_TAIL_DAYS)

        val byDay = events.groupBy { dateOf(it, zone) }

        val todayEvents = byDay[today].orEmpty().sortedBy { it.startUtcMillis }
        val tomorrowEvents = byDay[tomorrow].orEmpty().sortedBy { it.startUtcMillis }
        val weekEvents = events
            .filter { val d = dateOf(it, zone); !d.isBefore(weekStart) && d.isBefore(weekEndExclusive) }
            .sortedBy { it.startUtcMillis }

        return listOf(
            renderChapter(Bucket.TODAY, dayHeadline("Today", today), todayEvents, zone, includeWeekday = false),
            renderChapter(Bucket.TOMORROW, dayHeadline("Tomorrow", tomorrow), tomorrowEvents, zone, includeWeekday = false),
            renderChapter(Bucket.WEEK, "The rest of your week", weekEvents, zone, includeWeekday = true),
        )
    }

    // ─── rendering ──────────────────────────────────────────────────────────

    private fun renderChapter(
        bucket: Bucket,
        headline: String,
        events: List<CalendarEvent>,
        zone: ZoneId,
        includeWeekday: Boolean,
    ): Chapter {
        val allDay = events.filter { it.allDay }
        val timed = events.filter { !it.allDay }

        val plain = StringBuilder()
        val html = StringBuilder()

        plain.appendLine(headline)
        html.append("<p><strong>").append(escape(headline)).append("</strong></p>\n")

        if (events.isEmpty()) {
            val empty = emptyLine(bucket)
            plain.appendLine().appendLine(empty)
            html.append("<p>").append(escape(empty)).append("</p>\n")
            return Chapter(bucket, bucket.title, plain.toString().trim(), html.toString().trim(), 0)
        }

        val count = events.size
        val summary = "$count ${if (count == 1) "event" else "events"}."
        plain.appendLine().appendLine(summary)
        html.append("<p>").append(escape(summary)).append("</p>\n")

        // All-day events first, summarised.
        allDay.forEach { e ->
            val line = allDayLine(e, zone, includeWeekday)
            plain.appendLine().appendLine(line)
            html.append("<p>").append(escape(line)).append("</p>\n")
        }
        // Then timed events, chronological.
        timed.forEach { e ->
            val line = timedLine(e, zone, includeWeekday)
            plain.appendLine().appendLine(line)
            html.append("<p>").append(escape(line)).append("</p>\n")
        }

        return Chapter(bucket, bucket.title, plain.toString().trim(), html.toString().trim(), count)
    }

    private fun emptyLine(bucket: Bucket): String = when (bucket) {
        Bucket.TODAY -> "Nothing on your calendar today."
        Bucket.TOMORROW -> "Nothing scheduled for tomorrow."
        Bucket.WEEK -> "The rest of your week is clear."
    }

    private fun allDayLine(e: CalendarEvent, zone: ZoneId, includeWeekday: Boolean): String {
        val prefix = if (includeWeekday) "${weekday(dateOf(e, zone))}, all day" else "All day"
        return buildString {
            append(prefix).append(": ").append(titleOf(e))
            e.location?.takeIf { it.isNotBlank() }?.let { append(", at ").append(it.trim()) }
            append(".")
        }
    }

    private fun timedLine(e: CalendarEvent, zone: ZoneId, includeWeekday: Boolean): String {
        val start = Instant.ofEpochMilli(e.startUtcMillis).atZone(zone)
        val time = start.format(TIME_FMT)
        val prefix = if (includeWeekday) "${weekday(start.toLocalDate())}, $time" else time
        return buildString {
            append(prefix).append(" — ").append(titleOf(e))
            e.location?.takeIf { it.isNotBlank() }?.let { append(", at ").append(it.trim()) }
            durationPhrase(e)?.let { append(". ").append(it) }
            append(".")
        }
    }

    private fun durationPhrase(e: CalendarEvent): String? {
        val minutes = ((e.endUtcMillis - e.startUtcMillis) / 60_000L)
        if (minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0L -> "${m} ${plural(m, "minute")}"
            m == 0L -> "${h} ${plural(h, "hour")}"
            else -> "${h} ${plural(h, "hour")} ${m} ${plural(m, "minute")}"
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private fun today(nowMillis: Long, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()

    /**
     * The calendar date an event falls on. All-day events are stored as UTC
     * midnight by the provider, so their date is read in UTC; timed events use
     * the device zone.
     */
    private fun dateOf(e: CalendarEvent, zone: ZoneId): LocalDate =
        if (e.allDay) {
            Instant.ofEpochMilli(e.startUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
        } else {
            Instant.ofEpochMilli(e.startUtcMillis).atZone(zone).toLocalDate()
        }

    private fun dayHeadline(label: String, date: LocalDate): String =
        "$label, ${weekday(date)}, ${date.format(DATE_FMT)}"

    private fun weekday(date: LocalDate): String =
        date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())

    private fun titleOf(e: CalendarEvent): String =
        e.title.trim().ifBlank { "Untitled event" }

    private fun plural(n: Long, word: String): String = if (n == 1L) word else "${word}s"

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d", Locale.getDefault())
}
