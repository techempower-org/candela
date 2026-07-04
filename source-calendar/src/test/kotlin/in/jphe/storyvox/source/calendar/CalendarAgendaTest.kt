package `in`.jphe.storyvox.source.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId

/**
 * Pure agenda logic (#1495) — bucketing + narration, no Android, no clock.
 * Fixed reference: Friday 2026-07-03 09:00, zone UTC (keeps the millis math
 * and all-day UTC-midnight handling deterministic).
 */
class CalendarAgendaTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val friday = LocalDate.of(2026, 7, 3)
    private val now = friday.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun at(date: LocalDate, hour: Int, minute: Int): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun timed(
        title: String,
        date: LocalDate,
        hour: Int,
        minute: Int,
        durationMin: Long,
        location: String? = null,
    ) = CalendarEvent(
        id = title.hashCode().toLong(),
        title = title,
        startUtcMillis = at(date, hour, minute),
        endUtcMillis = at(date, hour, minute) + durationMin * 60_000L,
        allDay = false,
        location = location,
        calendarName = "Personal",
    )

    private fun allDay(title: String, date: LocalDate) = CalendarEvent(
        id = title.hashCode().toLong(),
        title = title,
        // Provider stores all-day events as UTC midnight of the date.
        startUtcMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        endUtcMillis = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        allDay = true,
        location = null,
        calendarName = "Holidays",
    )

    @Test
    fun queryWindowSpansSevenDaysFromStartOfToday() {
        val window = CalendarAgenda.queryWindowUtcMillis(now, zone)
        assertEquals(friday.atStartOfDay(zone).toInstant().toEpochMilli(), window.first)
        // 2 (today+tomorrow) + 5 (week tail) = 7 days later, exclusive.
        val expectedEnd = friday.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expectedEnd - 1, window.last)
    }

    @Test
    fun alwaysThreeChaptersInReadingOrder() {
        val chapters = CalendarAgenda.chapters(emptyList(), now, zone)
        assertEquals(3, chapters.size)
        assertEquals(CalendarAgenda.Bucket.TODAY, chapters[0].bucket)
        assertEquals(CalendarAgenda.Bucket.TOMORROW, chapters[1].bucket)
        assertEquals(CalendarAgenda.Bucket.WEEK, chapters[2].bucket)
    }

    @Test
    fun emptyChaptersReadGracefully() {
        val chapters = CalendarAgenda.chapters(emptyList(), now, zone)
        assertEquals(0, chapters[0].eventCount)
        assertTrue(chapters[0].plain.contains("Nothing on your calendar today"))
        assertTrue(chapters[1].plain.contains("Nothing scheduled for tomorrow"))
        assertTrue(chapters[2].plain.contains("The rest of your week is clear"))
    }

    @Test
    fun todayEventNarratesTimeTitleLocationDuration() {
        val events = listOf(timed("Standup", friday, 10, 0, 30, location = "Room 4"))
        val today = CalendarAgenda.chapters(events, now, zone)[0]
        assertEquals(1, today.eventCount)
        assertTrue(today.plain.contains("10:00 AM"))
        assertTrue(today.plain.contains("Standup"))
        assertTrue(today.plain.contains("at Room 4"))
        assertTrue(today.plain.contains("30 minutes"))
    }

    @Test
    fun allDayEventsAreSummarisedBeforeTimedOnes() {
        val events = listOf(
            timed("Standup", friday, 10, 0, 30),
            allDay("Independence Day", friday),
        )
        val today = CalendarAgenda.chapters(events, now, zone)[0]
        val allDayPos = today.plain.indexOf("All day: Independence Day")
        val timedPos = today.plain.indexOf("Standup")
        assertTrue("all-day should appear", allDayPos >= 0)
        assertTrue("timed should appear", timedPos >= 0)
        assertTrue("all-day summarised first", allDayPos < timedPos)
    }

    @Test
    fun durationPhrasingHandlesHoursAndMinutes() {
        val events = listOf(
            timed("Ninety", friday, 8, 0, 90),
            timed("ExactHour", friday, 12, 0, 60),
            timed("OneMinute", friday, 15, 0, 1),
        )
        val plain = CalendarAgenda.chapters(events, now, zone)[0].plain
        assertTrue(plain.contains("1 hour 30 minutes"))
        assertTrue(plain.contains("1 hour."))
        assertTrue(plain.contains("1 minute"))
    }

    @Test
    fun tomorrowBucketGetsTomorrowEvents() {
        val events = listOf(timed("Dentist", friday.plusDays(1), 9, 0, 45))
        val chapters = CalendarAgenda.chapters(events, now, zone)
        assertEquals(0, chapters[0].eventCount)
        assertEquals(1, chapters[1].eventCount)
        assertTrue(chapters[1].plain.contains("Dentist"))
    }

    @Test
    fun weekChapterIncludesWeekdayPrefixAndExcludesTodayTomorrow() {
        // Monday 2026-07-06 is inside the week tail (today=Fri, tomorrow=Sat).
        val monday = LocalDate.of(2026, 7, 6)
        val events = listOf(
            timed("Today event", friday, 10, 0, 30),
            timed("Planning", monday, 14, 0, 60),
        )
        val week = CalendarAgenda.chapters(events, now, zone)[2]
        assertEquals(1, week.eventCount)
        assertTrue(week.plain.contains("Planning"))
        assertTrue(week.plain.contains("Monday"))
        assertFalse(week.plain.contains("Today event"))
    }

    @Test
    fun blankTitleFallsBackToUntitled() {
        val events = listOf(timed("   ", friday, 11, 0, 15))
        assertTrue(CalendarAgenda.chapters(events, now, zone)[0].plain.contains("Untitled event"))
    }

    @Test
    fun htmlBodyIsParagraphWrappedAndEscaped() {
        val events = listOf(timed("A & B <tag>", friday, 10, 0, 30))
        val html = CalendarAgenda.chapters(events, now, zone)[0].html
        assertTrue(html.contains("<p>"))
        assertTrue(html.contains("A &amp; B &lt;tag&gt;"))
        assertFalse(html.contains("<tag>"))
    }
}
