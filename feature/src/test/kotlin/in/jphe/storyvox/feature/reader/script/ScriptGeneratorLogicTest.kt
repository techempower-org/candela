package `in`.jphe.storyvox.feature.reader.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1366 — AI script writer. The pace math (word-count target, duration
 * estimate) and prompt construction are extracted to pure functions so
 * they're pinned without Hilt, coroutines, or a live LLM provider — mirroring
 * [`in`.jphe.storyvox.feature.reader.TeleprompterScrollTest]. The streaming
 * orchestration itself is exercised on-device (the provider layer isn't
 * cheaply fakeable in a unit test).
 *
 * Backtick test names stay ASCII and avoid JVM-illegal chars (no `. : / [ ]`)
 * so these compile under Test Compile, not just Build APK.
 */
class ScriptGeneratorLogicTest {

    // ===== targetWordCount =====

    @Test
    fun `target word count is duration times pace over sixty`() {
        // 150 wpm is the speaking-pace constant; 60s of speech is 150 words.
        assertEquals(150, targetWordCount(60))
        assertEquals(75, targetWordCount(30))
        assertEquals(225, targetWordCount(90))
    }

    @Test
    fun `target word count honours a custom pace`() {
        assertEquals(120, targetWordCount(60, wpm = 120))
        assertEquals(60, targetWordCount(30, wpm = 120))
    }

    @Test
    fun `non-positive duration or pace yields zero words`() {
        assertEquals(0, targetWordCount(0))
        assertEquals(0, targetWordCount(-30))
        assertEquals(0, targetWordCount(60, wpm = 0))
    }

    // ===== scriptWordCount =====

    @Test
    fun `counts whitespace-delimited words`() {
        assertEquals(2, scriptWordCount("hello world"))
        assertEquals(1, scriptWordCount("solo"))
        // Collapsed whitespace runs (spaces, tabs, newlines) are one delimiter.
        assertEquals(4, scriptWordCount("one  two\tthree\nfour"))
    }

    @Test
    fun `blank or empty text has no words`() {
        assertEquals(0, scriptWordCount(""))
        assertEquals(0, scriptWordCount("   \t\n  "))
    }

    @Test
    fun `leading and trailing whitespace does not inflate the count`() {
        assertEquals(3, scriptWordCount("  one two three  "))
    }

    // ===== estimatedDurationSecs =====

    @Test
    fun `estimated duration inverts the pace`() {
        // 150 words at 150 wpm is exactly one minute.
        assertEquals(60, estimatedDurationSecs("word ".repeat(150).trim()))
        assertEquals(30, estimatedDurationSecs("word ".repeat(75).trim()))
    }

    @Test
    fun `estimated duration rounds to whole seconds`() {
        // 151 words at 150 wpm = 60.4s, rounds to 60.
        assertEquals(60, estimatedDurationSecs("word ".repeat(151).trim()))
        // 153 words at 150 wpm = 61.2s, rounds to 61.
        assertEquals(61, estimatedDurationSecs("word ".repeat(153).trim()))
    }

    @Test
    fun `empty text or zero pace estimates zero seconds`() {
        assertEquals(0, estimatedDurationSecs(""))
        assertEquals(0, estimatedDurationSecs("word", wpm = 0))
    }

    // ===== prompt construction =====

    @Test
    fun `system prompt interpolates the duration and word target`() {
        val prompt = buildScriptSystemPrompt(60)
        assertTrue(prompt.contains("60s at 150 words per minute (150 words)"))
        // The voice rules survive the trimIndent.
        assertTrue(prompt.contains("short-form video"))
        assertTrue(prompt.contains("hook"))
        assertTrue(prompt.contains("[pause]"))
        assertTrue(prompt.contains("call-to-action"))
    }

    @Test
    fun `system prompt scales the word target with duration`() {
        assertTrue(buildScriptSystemPrompt(30).contains("(75 words)"))
        assertTrue(buildScriptSystemPrompt(90).contains("(225 words)"))
    }

    @Test
    fun `user prompt carries the topic and length target`() {
        val prompt = buildScriptUserPrompt("Why accessibility matters", 60)
        assertTrue(prompt.contains("Why accessibility matters"))
        assertTrue(prompt.contains("60 seconds"))
        assertTrue(prompt.contains("150 words"))
    }

    @Test
    fun `user prompt trims topic whitespace`() {
        val prompt = buildScriptUserPrompt("   padded topic   ", 30)
        assertTrue(prompt.contains("Topic: padded topic\n"))
    }

    // ===== duration presets =====

    @Test
    fun `duration presets are the three short-form lengths`() {
        assertEquals(listOf(30, 60, 90), SCRIPT_DURATION_OPTIONS)
    }

    // ===== ScriptDraftStore =====

    @Test
    fun `draft store starts empty`() {
        assertNull(ScriptDraftStore().activeScript.value)
    }

    @Test
    fun `draft store load then clear round-trips the script`() {
        val store = ScriptDraftStore()
        store.load("Hook them in five words.")
        assertEquals("Hook them in five words.", store.activeScript.value)
        store.clear()
        assertNull(store.activeScript.value)
    }
}
