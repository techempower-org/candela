package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1287 — practice mode. Edge-case companion to [DialogueSegmenterTest],
 * focused on non-Latin / multi-language dialogue, unusual or unhandled quote
 * characters, nested / mismatched / unclosed quotes, multi-sentence quotes, and
 * the empty / whitespace / pure-narration inputs.
 *
 * Detection is deliberately double-quote-only (see [DialogueSegmenter] kdoc):
 * straight `"` toggles, curly `“`/`”` is an open/close pair. Curly single
 * quotes, guillemets, and every other quote style are NOT delimiters, so this
 * file asserts they stay narration rather than inventing support for them.
 *
 * Like the template, assertions reconstruct each segment's substring
 * (`text.substring(start, end)`) and check the tiling invariant rather than
 * hand-computing offsets.
 */
class DialogueSegmenterLanguageTest {

    private fun parts(text: String) =
        segmentDialogue(text).map { text.substring(it.start, it.end) }

    private fun kinds(text: String) = segmentDialogue(text).map { it.kind }

    /** Every segment list must tile [text] exactly: start at 0, end at length,
     *  each segment starts where the previous ended, and the concatenation of
     *  all runs reproduces the input. */
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

    // ===== unhandled quote styles → narration =====

    @Test
    fun `curly single quotes are not delimiters and stay narration`() {
        // ‘ ’ are the single-quote / apostrophe family — never delimiters — so
        // text wrapped in them is one narration run, not dialogue.
        val t = "‘Hello,’ said Tom."
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `guillemets are not delimiters and stay narration`() {
        // « » are not in the recognized set (only straight and curly double
        // quotes), so a guillemet-quoted line is pure narration.
        val t = "« Bonjour, » dit-il."
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `single straight apostrophe quoting stays narration`() {
        // A line quoted with straight single quotes uses ' which is the
        // apostrophe char, never a delimiter — so it remains narration.
        val t = "'Hi there,' she said."
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    // ===== non-Latin text INSIDE recognized quotes =====

    @Test
    fun `cyrillic inside straight quotes is one dialogue run`() {
        // Segmentation keys on the quote chars only; the Cyrillic payload is
        // carried verbatim inside a single dialogue run.
        val t = "\"Привет, как дела?\" сказал Иван."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("\"Привет, как дела?\"", " сказал Иван."), parts(t))
        assertTiles(t)
    }

    @Test
    fun `cjk inside curly quotes is one dialogue run`() {
        // Curly double quotes wrap CJK text → a single dialogue run, then the
        // trailing narration. No language detection is implied.
        val t = "“你好，世界。” 他说。"
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("“你好，世界。”", " 他说。"), parts(t))
        assertTiles(t)
    }

    @Test
    fun `emoji and combining marks inside a quote stay in the dialogue run`() {
        // Non-ASCII codepoints that aren't quote chars are just payload; the
        // run boundaries are still the surrounding double quotes.
        val t = "\"Café ☕ déjà vu\" he mused."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("\"Café ☕ déjà vu\"", " he mused."), parts(t))
        assertTiles(t)
    }

    // ===== nested / mismatched / unclosed quotes =====

    @Test
    fun `straight quote nested inside curly quote does not close the run`() {
        // Inside a curly-opened run only the curly close ‘”’ ends it; a stray
        // straight quote in between is part of the dialogue payload.
        val t = "“She said \"stop\" loudly,” he noted."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("“She said \"stop\" loudly,”", " he noted."), parts(t))
        assertTiles(t)
    }

    @Test
    fun `single-quoted clause inside straight dialogue does not split it`() {
        // Apostrophe-style single quotes inside a straight-quoted line are not
        // delimiters, so the whole thing is one dialogue run.
        val t = "\"He said 'hello' to me.\""
        assertEquals(listOf(SegmentKind.Dialogue), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `nested straight quotes toggle and reopen`() {
        // Straight quotes are open/close-ambiguous, so they toggle: the second
        // " closes the first run, the inner words become narration, and the
        // fourth " reopens a run that closes at the fifth.
        val t = "\"She said \"stop\" now.\""
        assertEquals(
            listOf(SegmentKind.Dialogue, SegmentKind.Narration, SegmentKind.Dialogue),
            kinds(t),
        )
        assertEquals(listOf("\"She said \"", "stop", "\" now.\""), parts(t))
        assertTiles(t)
    }

    @Test
    fun `mismatched curly open with straight close runs to end as dialogue`() {
        // A curly open sets the closer to the curly close; a straight quote
        // does not satisfy it, so the run continues to end-of-text.
        val t = "“This never gets a curly close\" and keeps going."
        assertEquals(listOf(SegmentKind.Dialogue), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `curly close before any open is plain narration`() {
        // A lone closing curly quote with no preceding open never enters a
        // quote, so the whole string is narration.
        val t = "Nothing opened here” really."
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `unclosed curly open runs to end as dialogue`() {
        val t = "He paused. “An open line that never closes"
        assertEquals(listOf(SegmentKind.Narration, SegmentKind.Dialogue), kinds(t))
        assertEquals(listOf("He paused. ", "“An open line that never closes"), parts(t))
        assertTiles(t)
    }

    // ===== quotes spanning multiple sentences =====

    @Test
    fun `a quote spanning multiple sentences is a single dialogue run`() {
        // Sentence punctuation inside a quote is not a boundary; only the
        // closing quote ends the run, so multi-sentence speech is one segment.
        val t = "\"Stop. Wait. Listen to me.\" he urged."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("\"Stop. Wait. Listen to me.\"", " he urged."), parts(t))
        assertTiles(t)
    }

    @Test
    fun `curly quote spanning multiple sentences is a single dialogue run`() {
        val t = "“Run! They are coming. Go now.” She bolted."
        assertEquals(listOf(SegmentKind.Dialogue, SegmentKind.Narration), kinds(t))
        assertEquals(listOf("“Run! They are coming. Go now.”", " She bolted."), parts(t))
        assertTiles(t)
    }

    // ===== empty / whitespace / pure narration =====

    @Test
    fun `whitespace-only text is a single narration segment`() {
        val t = "   \t\n  "
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `pure narration with no quotes is a single narration segment`() {
        val t = "The rain fell softly on the empty street as night drew in."
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `non-Latin narration with no quotes stays one narration segment`() {
        // No double quotes anywhere → one narration run regardless of script.
        val t = "夜の街に静かに雨が降っていた。"
        assertEquals(listOf(SegmentKind.Narration), kinds(t))
        assertEquals(listOf(t), parts(t))
        assertTiles(t)
    }

    @Test
    fun `empty string yields an empty list`() {
        // Mirrors the contract in DialogueSegmenter: empty input → no segments.
        assertEquals(emptyList<TextSegment>(), segmentDialogue(""))
    }
}
