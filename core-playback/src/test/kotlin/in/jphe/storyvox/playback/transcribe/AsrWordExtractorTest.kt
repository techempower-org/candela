package `in`.jphe.storyvox.playback.transcribe

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1368 — the stable-word policy that turns a streaming recognizer's
 * growing hypothesis into a stream of new words for the [ForcedAligner].
 */
class AsrWordExtractorTest {

    @Test
    fun `holds back the only word until more arrive`() {
        val ex = AsrWordExtractor()
        // A single in-flight token may still be revised — emit nothing yet.
        assertEquals(emptyList<String>(), ex.newWords("the"))
    }

    @Test
    fun `emits each word as the next one stabilizes it`() {
        val ex = AsrWordExtractor()
        assertEquals(emptyList<String>(), ex.newWords("the"))
        assertEquals(listOf("the"), ex.newWords("the quick"))
        assertEquals(listOf("quick"), ex.newWords("the quick brown"))
        // No growth → nothing new.
        assertEquals(emptyList<String>(), ex.newWords("the quick brown"))
    }

    @Test
    fun `emits a multi-word jump in order minus the unstable tail`() {
        val ex = AsrWordExtractor()
        assertEquals(listOf("alpha", "beta", "gamma"), ex.newWords("alpha beta gamma delta"))
    }

    @Test
    fun `flush releases the held tail and resets for the next utterance`() {
        val ex = AsrWordExtractor()
        ex.newWords("the quick brown") // emits the, quick; holds brown
        assertEquals(listOf("brown"), ex.flush("the quick brown"))
        // After a flush the count is reset — a brand-new utterance starts over.
        assertEquals(emptyList<String>(), ex.newWords("next"))
        assertEquals(listOf("next"), ex.newWords("next utterance"))
    }

    @Test
    fun `empty and whitespace hypotheses are safe no-ops`() {
        val ex = AsrWordExtractor()
        assertEquals(emptyList<String>(), ex.newWords(""))
        assertEquals(emptyList<String>(), ex.newWords("   "))
        assertEquals(emptyList<String>(), ex.flush(""))
    }

    @Test
    fun `reset drops in-flight progress`() {
        val ex = AsrWordExtractor()
        ex.newWords("one two three") // emitted = 2
        ex.reset()
        // Fresh start: "one two" emits only "one" again.
        assertEquals(listOf("one"), ex.newWords("one two"))
    }
}
