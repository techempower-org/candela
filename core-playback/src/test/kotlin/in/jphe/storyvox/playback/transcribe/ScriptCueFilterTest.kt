package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1368 — stripping cues + banners from a script and mapping the cleaned
 * offsets back to the source (so the scroll stays aligned to the displayed text).
 */
class ScriptCueFilterTest {

    @Test
    fun `strips an inline cue`() {
        val spoken = ScriptCueFilter.spokenText("Hi [POST: jingle] there")
        assertFalse(spoken.text.contains("POST"))
        assertFalse(spoken.text.contains("jingle"))
        assertTrue(spoken.text.contains("Hi"))
        assertTrue(spoken.text.contains("there"))
    }

    @Test
    fun `strips banner rule lines but keeps the dialogue`() {
        val source = "================\nWelcome back\n----------------\nto the show"
        val spoken = ScriptCueFilter.spokenText(source)
        assertFalse(spoken.text.contains("="))
        assertFalse(spoken.text.contains("-"))
        assertTrue(spoken.text.contains("Welcome back"))
        assertTrue(spoken.text.contains("to the show"))
    }

    @Test
    fun `maps a spoken offset back to the source index past a cue`() {
        // "there" starts at source index 18 (after the removed "[POST: jingle]").
        val source = "Hi [POST: jingle] there"
        assertEquals('t', source[18])
        val spoken = ScriptCueFilter.spokenText(source)
        // Find "there" in the cleaned text and map its 't' back to the source.
        val spokenIdx = spoken.text.indexOf("there")
        assertEquals(18, spoken.toSourceOffset(spokenIdx))
    }

    @Test
    fun `keeps speaker labels — the aligner tolerates the stray token`() {
        // The brief strips only cues + banners; "SHAWNA:" stays (the aligner's
        // resync absorbs the one unspoken token per turn).
        val spoken = ScriptCueFilter.spokenText("SHAWNA:\nWelcome back")
        assertTrue(spoken.text.contains("SHAWNA"))
    }

    @Test
    fun `empty source is a safe no-op`() {
        val spoken = ScriptCueFilter.spokenText("")
        assertEquals("", spoken.text)
        assertEquals(0, spoken.toSourceOffset(0))
    }

    @Test
    fun `offset zero maps to the start before any match`() {
        val spoken = ScriptCueFilter.spokenText("====\nWelcome")
        // Pre-match position (0) must map to a valid source index, not crash.
        assertTrue(spoken.toSourceOffset(0) in 0..("====\nWelcome".length))
    }
}
