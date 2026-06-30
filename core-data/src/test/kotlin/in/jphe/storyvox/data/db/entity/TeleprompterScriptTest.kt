package `in`.jphe.storyvox.data.db.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // ── Show-script format parsing (#1369 / TechEmpower Show) ──────────────

    @Test fun `spokenWordCount counts plain freeform text in full`() {
        assertEquals(4, TeleprompterScript.spokenWordCount("Just some plain words."))
    }

    @Test fun `spokenWordCount excludes bracketed cues, inline and multi-line`() {
        assertEquals(5, TeleprompterScript.spokenWordCount("Glad to be here [chuckles] really."))
        // Multi-line cue is removed whole — only "Before" + "after" remain spoken.
        val multi = "Before [POST: jingle\nand logo] after"
        assertEquals(2, TeleprompterScript.spokenWordCount(multi))
    }

    @Test fun `spokenWordCount excludes banner blocks, rules, and speaker labels`() {
        val script = """
            ================================================================
            WAIT, I QUALIFY?!  --  EPISODE 1
            ----------------------------------------------------------------
            PROMPTER NOTES (do not read):
            - Lines in [BRACKETS] are cues, not dialogue.
            ================================================================

            [POST: JINGLE + LOGO INTRO]

            SHAWNA:
            Welcome to the show.

            JEFF:
            Glad to be here [chuckles] really.

            ================================================================
            MYTH ONE: "BENEFITS ARE ONLY FOR THE DESTITUTE"
            ================================================================

            SHAWNA:
            Let's jump in.
        """.trimIndent()

        // Only the three dialogue lines are spoken: 4 + 5 + 3 = 12 words.
        assertEquals(12, TeleprompterScript.spokenWordCount(script))
        // The raw total is much larger (metadata, headers, labels, cues).
        assertTrue(
            "total word count must exceed spoken",
            TeleprompterScript.wordCount(script) > TeleprompterScript.spokenWordCount(script),
        )
    }

    @Test fun `estimateDurationSecs counts spoken words only`() {
        val script = """
            [intro music plays for a while with many bracketed words here]

            SHAWNA:
            Hello there.
        """.trimIndent()
        // Only "Hello there." is spoken → 2 words → ceil(2*60/150) = 1s.
        assertEquals(1, TeleprompterScript.estimateDurationSecs(script))
    }

    @Test fun `ScriptFormat fromName falls back to FREEFORM for unknown values`() {
        assertEquals(ScriptFormat.FULL_SHOW, ScriptFormat.fromName("FULL_SHOW"))
        assertEquals(ScriptFormat.FREEFORM, ScriptFormat.fromName("not-a-format"))
        assertEquals(ScriptFormat.FREEFORM, ScriptFormat.fromName(""))
    }
}
