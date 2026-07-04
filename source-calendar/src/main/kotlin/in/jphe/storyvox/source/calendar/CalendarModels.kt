package `in`.jphe.storyvox.source.calendar

/**
 * One resolved calendar event *instance* (#1495).
 *
 * A recurring event expands into one [CalendarEvent] per occurrence — this is
 * what [`CalendarContract.Instances`][android.provider.CalendarContract.Instances]
 * hands back, and it's exactly what an agenda reading wants ("standup at 10,
 * again tomorrow at 10"), so the source never has to expand RRULEs itself.
 *
 * Pure data — no Android types — so the whole agenda-building + narration layer
 * ([CalendarAgenda]) is unit-testable on the JVM with hand-built fixtures.
 *
 * @property startUtcMillis instance start, epoch-UTC millis. For timed events
 *  this is a real UTC instant; for [allDay] events the CalendarProvider stores
 *  it as UTC midnight of the event's date — [CalendarAgenda] reads all-day
 *  times in UTC to recover the intended calendar date (reading them in the
 *  device zone would shift the day for anyone west of UTC — a classic bug).
 */
data class CalendarEvent(
    val id: Long,
    val title: String,
    val startUtcMillis: Long,
    val endUtcMillis: Long,
    val allDay: Boolean,
    val location: String?,
    val calendarName: String?,
)
