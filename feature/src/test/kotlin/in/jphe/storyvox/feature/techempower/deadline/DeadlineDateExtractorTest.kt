package `in`.jphe.storyvox.feature.techempower.deadline

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1515 — pure-JVM coverage for the deadline date extractor. These
 * tests ARE the acceptance criteria for date extraction: English + Spanish
 * notices, numeric formats, cue detection, and past-date handling — all
 * offline arithmetic (airplane-mode-safe by construction).
 */
class DeadlineDateExtractorTest {

    private val today = LocalDate.of(2026, 7, 4)

    @Test
    fun `english respond-by extracts the deadline with its cue`() {
        val text = "IMPORTANT: To keep your LifeLine discount, please respond by August 31, 2026."
        val candidates = DeadlineDateExtractor.extract(text, today)

        val top = candidates.first()
        assertEquals(LocalDate.of(2026, 8, 31), top.date)
        assertEquals("respond by", top.cue)
        assertFalse(top.isPast)
        assertTrue(top.rawText.contains("August 31, 2026"))
    }

    @Test
    fun `spanish notice date extracts (31 de agosto de 2026)`() {
        val text = "Debe renovar antes del 31 de agosto de 2026 para no perder sus beneficios."
        val candidates = DeadlineDateExtractor.extract(text, today)

        val top = candidates.first()
        assertEquals(LocalDate.of(2026, 8, 31), top.date)
        assertNotNull("a Spanish cue should be detected", top.cue)
        assertTrue(top.rawText.contains("31 de agosto de 2026"))
    }

    @Test
    fun `spanish vence cue is detected`() {
        val text = "Su certificación vence el 15 de septiembre de 2026."
        val candidates = DeadlineDateExtractor.extract(text, today)
        assertEquals(LocalDate.of(2026, 9, 15), candidates.first().date)
        assertNotNull(candidates.first().cue)
    }

    @Test
    fun `us numeric date extracts`() {
        val text = "SAR-7 report due by: 09/30/2026. Return the form to your county office."
        val candidates = DeadlineDateExtractor.extract(text, today)
        assertEquals(LocalDate.of(2026, 9, 30), candidates.first().date)
    }

    @Test
    fun `iso date extracts`() {
        val candidates = DeadlineDateExtractor.extract("Deadline 2026-08-31 firm.", today)
        assertEquals(LocalDate.of(2026, 8, 31), candidates.first().date)
    }

    @Test
    fun `two-digit year is expanded to the 2000s`() {
        val candidates = DeadlineDateExtractor.extract("Renew by 8/31/26", today)
        assertEquals(LocalDate.of(2026, 8, 31), candidates.first().date)
    }

    @Test
    fun `day-first english month name parses`() {
        val candidates = DeadlineDateExtractor.extract("Please reply by 31 August 2026.", today)
        assertEquals(LocalDate.of(2026, 8, 31), candidates.first().date)
    }

    @Test
    fun `abbreviated month with period parses`() {
        val candidates = DeadlineDateExtractor.extract("Due Aug. 31, 2026", today)
        assertEquals(LocalDate.of(2026, 8, 31), candidates.first().date)
    }

    @Test
    fun `future cued date ranks above future uncued date`() {
        // A bare date (letter's print date) plus a real, cued deadline.
        val text = "Printed 07/10/2026.\nYou must respond by August 31, 2026 to renew."
        val candidates = DeadlineDateExtractor.extract(text, today)
        assertEquals(LocalDate.of(2026, 8, 31), candidates.first().date)
        assertEquals("respond by", candidates.first().cue)
    }

    @Test
    fun `past dates are flagged and de-prioritised`() {
        val text = "Notice mailed January 3, 2026. Respond by December 15, 2026."
        val candidates = DeadlineDateExtractor.extract(text, today)
        // Future deadline first…
        assertEquals(LocalDate.of(2026, 12, 15), candidates.first().date)
        assertFalse(candidates.first().isPast)
        // …the mailed (past) date is still surfaced, flagged past.
        val past = candidates.first { it.date == LocalDate.of(2026, 1, 3) }
        assertTrue(past.isPast)
    }

    @Test
    fun `duplicate dates collapse and keep the cued mention`() {
        val text = "August 31, 2026.\nYou must respond by August 31, 2026."
        val candidates = DeadlineDateExtractor.extract(text, today)
        val matches = candidates.filter { it.date == LocalDate.of(2026, 8, 31) }
        assertEquals(1, matches.size)
        assertEquals("respond by", matches.first().cue)
    }

    @Test
    fun `text with no date yields no candidates`() {
        val candidates = DeadlineDateExtractor.extract("Thank you for your application.", today)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `blank text yields no candidates`() {
        assertTrue(DeadlineDateExtractor.extract("   ", today).isEmpty())
    }

    @Test
    fun `impossible dates are rejected`() {
        // month 13, day 40 — must not throw, must not appear.
        val candidates = DeadlineDateExtractor.extract("Ref 13/40/2026 and 02/30/2026.", today)
        assertTrue(candidates.none { it.date.monthValue == 13 })
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `snippet carries the surrounding line`() {
        val text = "Line one.\nYou must respond by August 31, 2026 or lose coverage.\nLine three."
        val top = DeadlineDateExtractor.extract(text, today).first()
        assertTrue(top.snippet.contains("respond by August 31, 2026"))
        assertFalse("snippet is a single line", top.snippet.contains("Line one"))
    }

    @Test
    fun `no cue leaves the cue null`() {
        val candidates = DeadlineDateExtractor.extract("The event is on March 3, 2027.", today)
        val march = candidates.first { it.date == LocalDate.of(2027, 3, 3) }
        // "on" is not a cue; "before"/"due"/etc. absent → null.
        assertNull(march.cue)
    }
}
