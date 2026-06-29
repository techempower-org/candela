package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1287 — practice mode. Pins the narration/dialogue split that the
 * turn-taking flow relies on. Pure JVM (no Compose/Android), per the
 * project's testing convention for extracted logic.
 *
 * Assertions reconstruct each segment's substring (`text.substring(start,
 * end)`) rather than hand-computing offsets — readable and offset-bug-proof
 * — and check the tiling invariant (segments cover the whole string with no
 * gaps/overlaps).
 */
class DialogueSegmenterTest {

    private fun parts(text: String) =
        segmentDialogue(text).map { text.substring(it.start, it.end) }

    private fun kinds(text: String) = segmentDialogue(text).map { it.kind }

    /** Every segment list must tile [text] exactly: start at 0, end at length,
     *  each segment starts where the previous ended. */
    private fun assertTiles(text: String) {
        val segs = segmentDialogue(text)
        if (text.isEmpty()) {
            assertTrue(segs.isEmpty()); return
        }
        assertEquals(0, segs.first().start)
        assertEquals(text.length, segs.last().end)
        for (i in 1 until segs.size) assertEquals(segs[i - 1].end, segs[i].start)
        assertEquals(text, segs.joinToString("") { text.substring(it.start, it.end) })
    }

    @Test
    fun `empty text yields no segments`() {
        assertEquals(emptyList<TextSegment>(), segmentDialogue(""))
    }

    @Test
    fun `plain narration is a single narration segment`() {
        val t = "He walked into the room and sat down."
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
    }

    @Test
    fun `leading dialogue then narration`() {
        val t = "\"Hello,\" he said."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("\"Hello,\"", " he said."), parts(t))
        assertTiles(t)
    }

    @Test
    fun `narration wrapping dialogue`() {
        val t = "He turned. \"Wait!\" she cried."
        assertEquals(
            listOf(SegmentKind.Narration, SegmentKind.Dialogue, SegmentKind.Narration),
            kinds(t),
        )
        assertEquals(listOf("He turned. ", "\"Wait!\"", " she cried."), parts(t))
        assertTiles(t)
    }

    @Test
    fun `apostrophes inside dialogue do not split it`() {
        // The ' in don't / it's is an apostrophe, never a delimiter — the
        // whole quote stays one dialogue segment.
        val t = "\"I don't know, it's complicated.\""
        assertEquals(listOf(SegmentKind.Dialogue), kinds(t))
        assertEquals(listOf(t), parts(t))
    }

    @Test
    fun `curly quotes are detected`() {
        val t = "“Hi,” said Tom."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("“Hi,”", " said Tom."), parts(t))
        assertTiles(t)
    }

    @Test
    fun `unbalanced opening quote runs to end as dialogue`() {
        val t = "She paused. \"This was never closed"
        assertEquals(listOf(SegmentKind.Narration, SegmentKind.Dialogue), kinds(t))
        assertEquals(listOf("She paused. ", "\"This was never closed"), parts(t))
        assertTiles(t)
    }

    @Test
    fun `two back-to-back exchanges tile cleanly`() {
        val t = "\"Hi.\" \"Bye.\" they chorused."
        assertTiles(t)
        assertEquals(
            listOf(SegmentKind.Dialogue, SegmentKind.Narration, SegmentKind.Dialogue, SegmentKind.Narration),
            kinds(t),
        )
    }

    // ===== speaker attribution (best-effort) =====

    private fun firstDialogueSpeaker(text: String) =
        segmentDialogue(text).first { it.kind == SegmentKind.Dialogue }.speaker

    @Test
    fun `attributes speaker after the quote`() {
        assertEquals("Harry", firstDialogueSpeaker("\"Hello there,\" said Harry."))
    }

    @Test
    fun `attributes speaker before the quote`() {
        assertEquals("Hermione", firstDialogueSpeaker("Hermione asked, \"Why?\""))
    }

    @Test
    fun `pronoun is not mistaken for a name`() {
        // "She said" is capitalised at sentence start but isn't a name.
        assertNull(firstDialogueSpeaker("\"Run!\" She said quietly."))
        // lowercase pronoun also yields no name.
        assertNull(firstDialogueSpeaker("\"Hi,\" he said."))
    }

    // ===== turn-taking decision logic =====

    private val turnText = "He turned. \"Wait!\" she cried. \"Stop,\" he added."

    @Test
    fun `dialogueAt finds the segment the playhead is inside`() {
        val segs = segmentDialogue(turnText)
        val firstDlg = segs.first { it.kind == SegmentKind.Dialogue }
        // Inside the dialogue run → it's the user's turn for that segment.
        assertEquals(firstDlg, dialogueAt(segs, firstDlg.start))
        assertEquals(firstDlg, dialogueAt(segs, firstDlg.start + 1))
        // In narration → null (TTS keeps reading).
        assertNull(dialogueAt(segs, 0))
        // The exclusive end belongs to the following narration, not the run.
        assertNull(dialogueAt(segs, firstDlg.end))
    }

    @Test
    fun `nextDialogueAtOrAfter walks to the upcoming hand-off`() {
        val segs = segmentDialogue(turnText)
        val dialogues = segs.filter { it.kind == SegmentKind.Dialogue }
        assertEquals(dialogues[0], nextDialogueAtOrAfter(segs, 0))
        // From the end of the first dialogue, the next pause is the 2nd line.
        assertEquals(dialogues[1], nextDialogueAtOrAfter(segs, dialogues[0].end))
        // Past all dialogue → none (narrate to the end).
        assertNull(nextDialogueAtOrAfter(segs, turnText.length))
    }

    @Test
    fun `resumeOffsetAfter is the end of the dialogue run`() {
        val segs = segmentDialogue(turnText)
        val firstDlg = segs.first { it.kind == SegmentKind.Dialogue }
        assertEquals(firstDlg.end, resumeOffsetAfter(firstDlg))
    }
}
