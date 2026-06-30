package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Wrist teleprompter line derivation — splitting a chapter into glanceable
 * lines and locating the speaker's current + next line by char offset.
 */
class WristTeleprompterLinesTest {

    @Test
    fun `splits sentences and newlines preserving spans`() {
        val text = "One. Two!\nThree"
        val spans = splitTeleprompterLines(text)
        assertEquals(listOf("One.", "Two!", "Three"), spans.map { it.text })
        // Spans index back into the original string.
        assertEquals("One.", text.substring(spans[0].start, spans[0].end))
        assertEquals("Three", text.substring(spans[2].start, spans[2].end))
    }

    @Test
    fun `collapses ellipsis runs into one line — no punctuation crumbs`() {
        assertEquals(
            listOf("Wait...", "Go."),
            splitTeleprompterLines("Wait... Go.").map { it.text },
        )
    }

    @Test
    fun `drops blank lines from a script`() {
        assertEquals(
            listOf("A.", "B."),
            splitTeleprompterLines("A.\n\n\nB.").map { it.text },
        )
    }

    @Test
    fun `empty text yields no lines`() {
        assertEquals(emptyList<String>(), splitTeleprompterLines("   \n  ").map { it.text })
    }

    @Test
    fun `current line is the one containing the cursor and next is the following`() {
        val text = "One. Two! Three."
        val spans = splitTeleprompterLines(text)
        // Cursor inside "Two!" (index ~6).
        val lines = wristLinesAt(spans, positionChar = 6)
        assertEquals("Two!", lines.current)
        assertEquals("Three.", lines.next)
    }

    @Test
    fun `offset zero resolves to the first line before any match`() {
        val spans = splitTeleprompterLines("First line. Second line.")
        val lines = wristLinesAt(spans, positionChar = 0)
        assertEquals("First line.", lines.current)
        assertEquals("Second line.", lines.next)
    }

    @Test
    fun `last line has an empty next`() {
        val text = "Only. Last."
        val spans = splitTeleprompterLines(text)
        val lines = wristLinesAt(spans, positionChar = text.length - 1)
        assertEquals("Last.", lines.current)
        assertEquals("", lines.next)
    }

    @Test
    fun `no spans yields EMPTY`() {
        assertEquals(WristLines.EMPTY, wristLinesAt(emptyList(), positionChar = 5))
    }
}
