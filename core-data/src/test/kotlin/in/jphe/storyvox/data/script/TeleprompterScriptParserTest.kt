package `in`.jphe.storyvox.data.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1367 — pins the teleprompter script parser against the real
 * TechEmpower Show format (multi-speaker, `====` section banners, `[POST: ...]`
 * production cues), exercising the pure parser directly (no Android / Compose).
 */
class TeleprompterScriptTest {

    @Test
    fun `speakers, section banners, and cues classify in order`() {
        val raw = """
            [POST: JINGLE + LOGO INTRO -- hold one beat]

            SHAWNA:
            Welcome back to the show. I'm Shawna.

            JEFF:
            And I'm Jeff.

            ================================================================
            BENEFIT ONE: THE ZERO-DOLLAR PHONE PLAN
            ================================================================

            SHAWNA:
            Okay -- start with the phone [POST: card on screen] now.
        """.trimIndent()

        val blocks = parseTeleprompterScript(raw).blocks

        // [POST...] standalone cue.
        assertTrue(blocks[0] is ScriptBlock.Cue)

        // First speaker → colorIndex 0, label shown.
        val shawna = blocks[1] as ScriptBlock.Line
        assertEquals("SHAWNA", shawna.speaker?.name)
        assertEquals(0, shawna.speaker?.colorIndex)
        assertTrue(shawna.showLabel)

        // Second distinct speaker → colorIndex 1.
        val jeff = blocks[2] as ScriptBlock.Line
        assertEquals("JEFF", jeff.speaker?.name)
        assertEquals(1, jeff.speaker?.colorIndex)

        // `BENEFIT ONE:` looks like a speaker tag but is framed by `=` rows, so
        // it must classify as a Section (banner-before-speaker ordering).
        val section = blocks[3] as ScriptBlock.Section
        assertTrue(section.title.contains("BENEFIT ONE"))

        // Re-used speaker keeps colorIndex 0; the inline cue splits out.
        val shawnaAgain = blocks[4] as ScriptBlock.Line
        assertEquals(0, shawnaAgain.speaker?.colorIndex)
        assertTrue(shawnaAgain.segments.any { it is LineSegment.InlineCue })
        assertTrue(shawnaAgain.segments.any { it is LineSegment.Spoken })
    }

    @Test
    fun `inline and standalone cues are excluded from spoken word count`() {
        val raw = """
            [POST: this whole block is an ignored cue]

            JEFF:
            Two words [POST: ignored cue words here] more text.
        """.trimIndent()

        // Spoken = "Two words" + "more text." = 4; cues contribute nothing.
        assertEquals(4, parseTeleprompterScript(raw).spokenWordCount)
    }

    @Test
    fun `plain prose with no markers is one unattributed line`() {
        val raw = "It was a bright cold day in April and the clocks were striking thirteen."
        val parsed = parseTeleprompterScript(raw)
        assertEquals(1, parsed.blocks.size)
        assertNull((parsed.blocks[0] as ScriptBlock.Line).speaker)
        assertEquals(14, parsed.spokenWordCount)
    }

    @Test
    fun `lowercase or parenthetical labels are not treated as speakers`() {
        val raw = """
            Hosts: Shawna and Jeff are here.

            PROMPTER NOTES (do not read):
            don't read this part aloud.
        """.trimIndent()

        parseTeleprompterScript(raw).blocks.forEach { block ->
            if (block is ScriptBlock.Line) assertNull(block.speaker)
        }
    }
}
