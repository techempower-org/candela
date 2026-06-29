package `in`.jphe.storyvox.playback.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1283 — heuristic dialogue attribution. Pure logic (no Android),
 * so it runs as a plain JUnit test. Assertions check the attributed
 * speaker name and that the segment offsets bracket the intended quoted
 * span (via `text.substring(start, end)`), rather than hardcoding brittle
 * absolute offsets.
 */
class DialogueAttributorTest {

    private val attr = DialogueAttributor()

    private fun span(text: String, seg: DialogueSegment): String =
        text.substring(seg.startOffset, seg.endOffset)

    // ── trailing attribution: "...," said Alice. ─────────────────────────

    @Test
    fun trailingSaidAttribution_namesSpeaker() {
        val text = "“Hello there,” said Alice."
        val segs = attr.attribute(text)
        assertEquals(1, segs.size)
        assertEquals("Alice", segs[0].characterName)
        assertTrue(span(text, segs[0]).contains("Hello there"))
    }

    @Test
    fun trailingAttribution_straightQuotes() {
        val text = "\"Get down!\" shouted Bob."
        val segs = attr.attribute(text)
        assertEquals(1, segs.size)
        assertEquals("Bob", segs[0].characterName)
    }

    // ── leading attribution: Bob said, "..." ─────────────────────────────

    @Test
    fun leadingSaidAttribution_namesSpeaker() {
        val text = "Bob said, “Goodbye.”"
        val segs = attr.attribute(text)
        assertEquals(1, segs.size)
        assertEquals("Bob", segs[0].characterName)
        assertTrue(span(text, segs[0]).contains("Goodbye"))
    }

    // ── multiple speakers in one passage ─────────────────────────────────

    @Test
    fun twoSpeakers_attributedIndependently() {
        val text = "“Where were you?” asked Alice. “Out,” Bob replied."
        val segs = attr.attribute(text)
        assertEquals(2, segs.size)
        assertEquals("Alice", segs[0].characterName)
        assertEquals("Bob", segs[1].characterName)
        // Segments are returned in document order.
        assertTrue(segs[0].startOffset < segs[1].startOffset)
    }

    // ── nested / inner quotes must not split the outer span ──────────────

    @Test
    fun innerSingleQuotes_doNotSplitOuterSpan() {
        val text = "“He told me ‘run’ and I ran,” Alice said."
        val segs = attr.attribute(text)
        assertEquals(1, segs.size)
        assertEquals("Alice", segs[0].characterName)
        // The whole outer quote (including the inner 'run') is one segment.
        assertTrue(span(text, segs[0]).contains("run"))
        assertTrue(span(text, segs[0]).contains("I ran"))
    }

    // ── narration-only passages yield nothing ────────────────────────────

    @Test
    fun narrationOnly_returnsEmpty() {
        val text = "The rain fell on the empty street. Nobody spoke."
        assertEquals(emptyList<DialogueSegment>(), attr.attribute(text))
    }

    @Test
    fun unattributedQuote_isNotEmitted() {
        // No name anywhere near the quote → we don't guess; narrator handles it.
        val text = "“Who's there?”"
        assertEquals(emptyList<DialogueSegment>(), attr.attribute(text))
    }

    @Test
    fun emptyText_returnsEmpty() {
        assertEquals(emptyList<DialogueSegment>(), attr.attribute(""))
    }

    // ── offsets are valid + non-overlapping + in range ───────────────────

    @Test
    fun segmentsAreInRangeAndOrdered() {
        val text = "“One,” said Alice. “Two,” said Bob. “Three,” said Cara."
        val segs = attr.attribute(text)
        assertEquals(3, segs.size)
        var prevEnd = 0
        for (s in segs) {
            assertTrue("start in range", s.startOffset in 0..text.length)
            assertTrue("end in range", s.endOffset in 0..text.length)
            assertTrue("start < end", s.startOffset < s.endOffset)
            assertTrue("ordered + non-overlapping", s.startOffset >= prevEnd)
            prevEnd = s.endOffset
        }
    }
}
