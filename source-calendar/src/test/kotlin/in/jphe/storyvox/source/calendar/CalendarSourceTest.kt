package `in`.jphe.storyvox.source.calendar

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * [CalendarSource] glue over a hand-built [CalendarReader] (#1495). No Android,
 * no contract kit — this is a local-provider source (source-ocr precedent).
 */
class CalendarSourceTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val friday = LocalDate.of(2026, 7, 3)
    private val now = friday.atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private class FakeReader(
        private val granted: Boolean,
        private val events: List<CalendarEvent> = emptyList(),
    ) : CalendarReader {
        override fun hasPermission(): Boolean = granted
        override suspend fun events(beginUtcMillis: Long, endUtcMillis: Long): List<CalendarEvent> =
            events.filter { it.startUtcMillis in beginUtcMillis until endUtcMillis }
    }

    private fun source(reader: CalendarReader) =
        CalendarSource(reader, { now }, { zone })

    private fun event(title: String, hour: Int, min: Int, durMin: Long): CalendarEvent {
        val start = LocalDateTime.of(friday, LocalTime.of(hour, min))
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        return CalendarEvent(1, title, start, start + durMin * 60_000L, false, null, "Personal")
    }

    @Test
    fun deniedPermissionYieldsEmptyListing() = runTest {
        val res = source(FakeReader(granted = false)).popular(1)
        assertTrue(res is FictionResult.Success)
        assertTrue((res as FictionResult.Success).value.items.isEmpty())
    }

    @Test
    fun grantedPermissionYieldsSingleCalendarFiction() = runTest {
        val res = source(FakeReader(granted = true)).popular(1)
        assertTrue(res is FictionResult.Success)
        val items = (res as FictionResult.Success).value.items
        assertEquals(1, items.size)
        assertEquals(CalendarSource.FICTION_ID, items[0].id)
        assertEquals("My Calendar", items[0].title)
        assertEquals("calendar", items[0].sourceId)
    }

    @Test
    fun latestUpdatesMirrorsPopular() = runTest {
        val res = source(FakeReader(granted = true)).latestUpdates(1)
        assertEquals(1, ((res as FictionResult.Success).value.items).size)
    }

    @Test
    fun fictionDetailHasThreeChapters() = runTest {
        val res = source(FakeReader(granted = true, events = listOf(event("Standup", 10, 0, 30))))
            .fictionDetail(CalendarSource.FICTION_ID)
        assertTrue(res is FictionResult.Success)
        val detail = (res as FictionResult.Success).value
        assertEquals(3, detail.chapters.size)
        assertEquals("Today", detail.chapters[0].title)
        assertTrue((detail.chapters[0].wordCount ?: 0) > 0)
    }

    @Test
    fun fictionDetailWhenDeniedIsAuthRequired() = runTest {
        val res = source(FakeReader(granted = false)).fictionDetail(CalendarSource.FICTION_ID)
        assertTrue(res is FictionResult.AuthRequired)
    }

    @Test
    fun unknownFictionIsNotFound() = runTest {
        val res = source(FakeReader(granted = true)).fictionDetail("nope")
        assertTrue(res is FictionResult.NotFound)
    }

    @Test
    fun chapterReturnsNarratedAgenda() = runTest {
        val res = source(FakeReader(granted = true, events = listOf(event("Standup", 10, 0, 30))))
            .chapter(CalendarSource.FICTION_ID, "${CalendarSource.FICTION_ID}::today")
        assertTrue(res is FictionResult.Success)
        val content = (res as FictionResult.Success).value
        assertTrue(content.plainBody.contains("Standup"))
        assertTrue(content.plainBody.contains("10:00 AM"))
        assertTrue(content.htmlBody.contains("<p>"))
    }

    @Test
    fun unknownChapterIsNotFound() = runTest {
        val res = source(FakeReader(granted = true))
            .chapter(CalendarSource.FICTION_ID, "${CalendarSource.FICTION_ID}::yesteryear")
        assertTrue(res is FictionResult.NotFound)
    }

    @Test
    fun latestRevisionTokenIsTodaysDate() = runTest {
        val res = source(FakeReader(granted = true)).latestRevisionToken(CalendarSource.FICTION_ID)
        assertEquals("2026-07-03", (res as FictionResult.Success).value)
    }

    @Test
    fun followsAndSetFollowedAreBenign() = runTest {
        val src = source(FakeReader(granted = true))
        assertTrue((src.followsList(1) as FictionResult.Success).value.items.isEmpty())
        assertTrue(src.setFollowed("x", true) is FictionResult.Success)
    }
}
