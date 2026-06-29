package `in`.jphe.storyvox.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1369 — pure unit coverage for [TeleprompterScript]'s word-count and
 * read-aloud duration math. No Android/Room/Robolectric dependency.
 */
class TeleprompterScriptTest {

    @Test fun `wordCount counts whitespace-delimited tokens`() {
        assertEquals(0, TeleprompterScript.wordCount(""))
        assertEquals(0, TeleprompterScript.wordCount("   \n\t "))
        assertEquals(1, TeleprompterScript.wordCount("hello"))
        assertEquals(3, TeleprompterScript.wordCount("one two three"))
        assertEquals(3, TeleprompterScript.wordCount("  one\ntwo\t three  "))
    }

    @Test fun `estimateDurationSecs is zero for an empty body`() {
        assertEquals(0, TeleprompterScript.estimateDurationSecs(""))
        assertEquals(0, TeleprompterScript.estimateDurationSecs("   "))
    }

    @Test fun `estimateDurationSecs rounds up so a one-word script is never zero`() {
        // 1 word @ 150 wpm = 0.4s → ceil → 1s.
        assertEquals(1, TeleprompterScript.estimateDurationSecs("hello"))
    }

    @Test fun `estimateDurationSecs at the 150 wpm baseline`() {
        // Exactly 150 words @ 150 wpm = 60s.
        val body = (1..150).joinToString(" ") { "w$it" }
        assertEquals(60, TeleprompterScript.estimateDurationSecs(body))
    }

    @Test fun `estimateDurationSecs honors a custom wpm`() {
        // 130 words @ 130 wpm = 60s.
        val body = (1..130).joinToString(" ") { "w$it" }
        assertEquals(60, TeleprompterScript.estimateDurationSecs(body, wpm = 130))
    }

    @Test fun `estimateDurationSecs clamps a non-positive wpm to avoid div-by-zero`() {
        // wpm coerced to >= 1; "a b" = 2 words @ 1 wpm = 120s.
        assertEquals(120, TeleprompterScript.estimateDurationSecs("a b", wpm = 0))
    }
}
