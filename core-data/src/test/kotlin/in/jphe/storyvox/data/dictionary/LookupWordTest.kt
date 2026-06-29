package `in`.jphe.storyvox.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1230 — word-extraction unit coverage. The reader hands
 * [normalizeLookupWord] whatever `getWordBoundary` grabbed under the finger;
 * these pin the squaring-up to a clean Wiktionary lemma, and [lemmaCandidates]
 * to the minimal set of case variants worth querying.
 */
class LookupWordTest {

    @Test
    fun `plain lowercase word is returned unchanged`() {
        assertEquals("serendipity", normalizeLookupWord("serendipity"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("phlegmatic", normalizeLookupWord("  phlegmatic\n"))
    }

    @Test
    fun `leading and trailing punctuation is stripped`() {
        assertEquals("Gatsby", normalizeLookupWord("“Gatsby,”"))
        assertEquals("end", normalizeLookupWord("end."))
        assertEquals("wait", normalizeLookupWord("(wait)"))
        assertEquals("really", normalizeLookupWord("really…"))
    }

    @Test
    fun `trailing possessive is removed`() {
        assertEquals("dog", normalizeLookupWord("dog's"))
        assertEquals("dog", normalizeLookupWord("dog’s"))
        assertEquals("dogs", normalizeLookupWord("dogs'"))
    }

    @Test
    fun `internal hyphen and apostrophe survive`() {
        assertEquals("mother-in-law", normalizeLookupWord("mother-in-law"))
        assertEquals("don't", normalizeLookupWord("don't"))
    }

    @Test
    fun `case is preserved for proper nouns`() {
        assertEquals("London", normalizeLookupWord("London"))
    }

    @Test
    fun `tokens with no letters are rejected`() {
        assertNull(normalizeLookupWord(""))
        assertNull(normalizeLookupWord("   "))
        assertNull(normalizeLookupWord("1234"))
        assertNull(normalizeLookupWord("--—--"))
        assertNull(normalizeLookupWord("?!?"))
    }

    @Test
    fun `lowercase word yields a single candidate`() {
        assertEquals(listOf("serendipity"), lemmaCandidates("serendipity"))
    }

    @Test
    fun `sentence-initial capital adds a decapitalised candidate`() {
        assertEquals(listOf("The", "the"), lemmaCandidates("The"))
    }

    @Test
    fun `all-caps acronym adds a fully lowercased candidate`() {
        // "NASA" -> as-seen, then first-letter-lower ("nASA"), then full lower ("nasa").
        assertEquals(listOf("NASA", "nASA", "nasa"), lemmaCandidates("NASA"))
    }

    @Test
    fun `candidate list never repeats a variant`() {
        // A capitalised word whose decapitalised form equals its lowercase form
        // must not list "serendipity" twice.
        assertEquals(listOf("Serendipity", "serendipity"), lemmaCandidates("Serendipity"))
    }

    @Test
    fun `blank input yields no candidates`() {
        assertEquals(emptyList<String>(), lemmaCandidates(""))
    }
}
