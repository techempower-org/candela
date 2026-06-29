package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1291 — the alignment policy is the load-bearing, fully-deterministic
 * core of the voice-paced teleprompter, so it gets exercised against the
 * recognizer's real failure modes: exact match, partial words,
 * skipped words, garbled input (hold), and re-sync after drift.
 */
class ForcedAlignerTest {

    private val text =
        "The quick brown fox jumps over the lazy dog near the river bank."

    private fun aligner() = ForcedAligner(text)

    @Test
    fun `exact words advance the cursor token by token`() {
        val a = aligner()
        a.onWord("the"); assertEquals(0, a.matchedTokenIndex)
        a.onWord("quick"); assertEquals(1, a.matchedTokenIndex)
        a.onWord("brown"); assertEquals(2, a.matchedTokenIndex)
        a.onWord("fox"); assertEquals(3, a.matchedTokenIndex)
        // positionChar is the start of "fox" in the original text.
        assertEquals(text.indexOf("fox"), a.positionChar)
    }

    @Test
    fun `partial recognized word still matches via prefix`() {
        val a = aligner()
        a.onWord("the")
        a.onWord("qui") // cut-off "quick"
        assertEquals(1, a.matchedTokenIndex)
    }

    @Test
    fun `near-miss recognition matches within edit-distance threshold`() {
        val a = aligner()
        a.onWord("the")
        a.onWord("quik") // misheard "quick" — 1 edit / 5 = 0.2 < 0.34
        assertEquals(1, a.matchedTokenIndex)
    }

    @Test
    fun `skipped words jump the cursor ahead within the window`() {
        val a = aligner()
        a.onWord("the")
        a.onWord("fox") // skipped quick + brown
        assertEquals(3, a.matchedTokenIndex)
    }

    @Test
    fun `garbled input holds position`() {
        val a = aligner()
        a.onWord("the"); a.onWord("quick")
        val held = a.matchedTokenIndex
        a.onWord("zzzzz") // nonsense — no token within threshold
        assertEquals(held, a.matchedTokenIndex)
    }

    @Test
    fun `re-syncs to a far-ahead word after a run of misses`() {
        // Narrow window so the re-sync target is genuinely out of range
        // (the production default ~30 would cover this short text in-window).
        val a = ForcedAligner(text, windowAhead = 4)
        a.onWord("the") // cursor 0; window covers tokens 0..4
        a.onWord("zzz1"); a.onWord("zzz2"); a.onWord("zzz3") // 3 misses
        // "river" (token 11) is beyond the window; the 4th miss triggers the
        // wide re-sync sweep, which finds it.
        a.onWord("river")
        assertEquals(11, a.matchedTokenIndex)
    }

    @Test
    fun `low-confidence words are held (cough or aside), not matched`() {
        val a = aligner()
        a.onWord("the"); a.onWord("quick")
        val held = a.matchedTokenIndex
        // The next real word arrives low-confidence (cough mid-sentence) — hold.
        a.onWord("brown", confidence = 0.2f)
        assertEquals(held, a.matchedTokenIndex)
        // A subsequent confident hit advances normally (no drift was recorded).
        a.onWord("brown", confidence = 0.95f)
        assertEquals(2, a.matchedTokenIndex)
    }

    @Test
    fun `contractions and case are normalized on both sides`() {
        val a = ForcedAligner("Don't stop now")
        a.onWord("DONT") // upper-case, no apostrophe
        assertEquals(0, a.matchedTokenIndex)
        a.onWord("stop")
        assertEquals(1, a.matchedTokenIndex)
    }

    @Test
    fun `very short noise words are ignored`() {
        val a = aligner()
        a.onWord("the"); a.onWord("quick")
        val held = a.matchedTokenIndex
        a.onWord("a") // below MIN_TOKEN_LEN
        assertEquals(held, a.matchedTokenIndex)
    }

    @Test
    fun `empty chapter text is a safe no-op`() {
        val a = ForcedAligner("")
        assertEquals(0, a.tokenCount)
        assertEquals(0, a.onWord("anything"))
        assertEquals(-1, a.matchedTokenIndex)
    }

    @Test
    fun `reset returns to the start`() {
        val a = aligner()
        a.onWord("the"); a.onWord("quick")
        a.reset()
        assertEquals(-1, a.matchedTokenIndex)
        assertEquals(0, a.positionChar)
    }

    @Test
    fun `tokenizer keeps contractions whole and records spans`() {
        val toks = tokenizeWords("Don't stop!")
        assertEquals(2, toks.size)
        assertEquals("dont", toks[0].norm)
        assertEquals(0, toks[0].startChar)
        assertEquals(5, toks[0].endChar) // "Don't"
        assertEquals("stop", toks[1].norm)
        assertEquals(6, toks[1].startChar)
    }

    @Test
    fun `edit distance basics`() {
        assertEquals(0, editDistance("abc", "abc"))
        assertEquals(3, editDistance("kitten", "sitting").let { it }) // classic = 3
        assertEquals(3, editDistance("", "abc"))
        assertTrue(editDistance("quick", "kwick") <= 2)
    }
}
