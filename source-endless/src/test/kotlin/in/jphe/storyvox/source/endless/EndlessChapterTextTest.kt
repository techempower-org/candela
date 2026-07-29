package `in`.jphe.storyvox.source.endless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing the daemon's `[speaker] …` markdown into reader + TTS bodies. */
class EndlessChapterTextTest {

    private val md = """
        # Chapter 5: The Mill at Blackwater

        [narrator] The rain did not wash things clean.

        [SYSTEM] Quest updated: 1/3 seals broken.

        [Sera] "We should go."
    """.trimIndent()

    @Test fun `blocks drop the heading and split on speaker tags`() {
        val blocks = EndlessChapterText.blocks(md)
        assertEquals(listOf("narrator", "SYSTEM", "Sera"), blocks.map { it.speaker })
        assertEquals("The rain did not wash things clean.", blocks[0].text)
    }

    @Test fun `plainBody carries no speaker tags`() {
        val plain = EndlessChapterText.plainBody(md)
        // The whole point: TTS must not pronounce the word "narrator" at
        // the top of every block.
        assertTrue(plain, !plain.contains("["))
        assertTrue(plain.startsWith("The rain did not wash things clean."))
    }

    @Test fun `plainBody separates blocks with a blank line for paragraph cadence`() {
        // core-playback's chunker reads a blank line as a paragraph
        // boundary, which is what gives status readouts their own beat.
        assertEquals(2, Regex("\\n\\n").findAll(EndlessChapterText.plainBody(md)).count())
    }

    @Test fun `plainBody omits the chapter heading`() {
        assertTrue(!EndlessChapterText.plainBody(md).contains("Chapter 5"))
    }

    @Test fun `htmlBody marks SYSTEM blocks as quoted panels and labels dialogue`() {
        val html = EndlessChapterText.htmlBody(md)
        assertTrue(html, html.contains("<blockquote><p>Quest updated: 1/3 seals broken.</p></blockquote>"))
        assertTrue(html, html.contains("<strong>Sera:</strong>"))
        // narrator is the default voice — labelling every narration block
        // would be noise.
        assertTrue(html, !html.contains("<strong>narrator:</strong>"))
    }

    @Test fun `htmlBody escapes markup characters in generated prose`() {
        val html = EndlessChapterText.htmlBody("[SYSTEM] HP <10 & falling")
        assertTrue(html, html.contains("&lt;10 &amp; falling"))
        assertTrue("raw < must not survive into the body", !html.contains("<10"))
    }

    @Test fun `untagged prose is kept rather than silently dropped`() {
        // The daemon tags every block today, but losing story because a tag
        // was missing is the one outcome worth guarding against.
        val plain = EndlessChapterText.plainBody("Bare prose with no tag at all.")
        assertEquals("Bare prose with no tag at all.", plain)
    }

    @Test fun `an unknown speaker needs no client change`() {
        val blocks = EndlessChapterText.blocks("[Brand-New Character] Hello.")
        assertEquals("Brand-New Character", blocks.single().speaker)
        assertEquals("Hello.", blocks.single().text)
    }

    @Test fun `empty and heading-only input yield empty bodies, not crashes`() {
        assertEquals("", EndlessChapterText.plainBody(""))
        assertEquals("", EndlessChapterText.plainBody("# Chapter 9: Title only"))
        assertEquals("", EndlessChapterText.htmlBody(""))
    }

    @Test fun `a tag with no text after it is dropped`() {
        // A speaker tag with an empty body would otherwise render as an
        // empty paragraph and give TTS nothing to say.
        assertEquals("", EndlessChapterText.plainBody("[narrator]"))
    }
}
