package `in`.jphe.storyvox.source.pdf.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1428 — tests for [reflowPdfText]. PdfBox's `PDFTextStripper`
 * emits one `\n` per laid-out line, so a wrapped paragraph arrives as
 * several hard-broken lines. These pin the soft-break → space reflow while
 * proving real structure (blank lines, lists, indented paragraph starts) is
 * preserved.
 */
class ReflowPdfTextTest {

    @Test
    fun `soft line wrap within a paragraph merges into spaces`() {
        val input = "The quick brown fox\njumps over the lazy\ndog near the riverbank."
        assertEquals(
            "The quick brown fox jumps over the lazy dog near the riverbank.",
            reflowPdfText(input),
        )
    }

    @Test
    fun `blank line separates paragraphs`() {
        val input = "First paragraph wraps\nover two lines.\n\nSecond paragraph\nalso wraps."
        assertEquals(
            "First paragraph wraps over two lines.\n\nSecond paragraph also wraps.",
            reflowPdfText(input),
        )
    }

    @Test
    fun `runs of blank lines collapse to one break`() {
        assertEquals("A line\n\nB line", reflowPdfText("A line\n\n\n\nB line"))
    }

    @Test
    fun `end of line hyphen with lowercase tail is de-hyphenated`() {
        assertEquals("international law applies", reflowPdfText("inter-\nnational law applies"))
    }

    @Test
    fun `end of line hyphen before a capital keeps the hyphen`() {
        assertEquals("Anglo-Saxon kings ruled", reflowPdfText("Anglo-\nSaxon kings ruled"))
    }

    @Test
    fun `carriage returns are normalized`() {
        assertEquals("line one line two", reflowPdfText("line one\r\nline two"))
        assertEquals("a\n\nb", reflowPdfText("a\r\n\r\nb"))
    }

    @Test
    fun `bullet list items are not merged together`() {
        val out = reflowPdfText("Shopping list\n- milk\n- eggs\n- bread")
        // Each item survives as its own block — never run together.
        assertTrue(out.contains("- milk"))
        assertTrue(out.contains("- eggs"))
        assertFalse(out.contains("milk - eggs"))
        assertEquals(4, out.split("\n\n").size)
    }

    @Test
    fun `numbered list items stay separate`() {
        val out = reflowPdfText("1. First item\n2. Second item\n3. Third item")
        assertEquals(3, out.split("\n\n").size)
        assertFalse(out.contains("item 2"))
    }

    @Test
    fun `indented line after a sentence starts a new paragraph`() {
        val input = "End of the first paragraph here.\n   The next paragraph is indented."
        assertEquals(
            "End of the first paragraph here.\n\nThe next paragraph is indented.",
            reflowPdfText(input),
        )
    }

    @Test
    fun `indented continuation without a sentence end still merges`() {
        // Indent alone must NOT split — only indent + a completed sentence.
        val input = "a wrapped clause ending mid\n   thought continues here"
        assertEquals("a wrapped clause ending mid thought continues here", reflowPdfText(input))
    }

    @Test
    fun `blank or whitespace input yields empty string`() {
        assertEquals("", reflowPdfText(""))
        assertEquals("", reflowPdfText("   \n\n  \t "))
    }

    @Test
    fun `repeated spaces and tabs collapse to one space`() {
        assertEquals("word1 word2 word3", reflowPdfText("word1    word2\tword3"))
    }

    @Test
    fun `leading and trailing blank lines are trimmed`() {
        assertEquals("Hello world", reflowPdfText("\n\n  Hello world  \n\n"))
    }

    @Test
    fun `a realistic multi-line paragraph reflows to flowing prose`() {
        val extracted = buildString {
            append("Dear applicant,\n")
            append("we are writing to inform you that your\n")
            append("benefits have been approved effective\n")
            append("the first of next month.\n")
            append("\n")
            append("Please retain this letter for your\n")
            append("records.")
        }
        assertEquals(
            "Dear applicant, we are writing to inform you that your benefits have been " +
                "approved effective the first of next month.\n\n" +
                "Please retain this letter for your records.",
            reflowPdfText(extracted),
        )
    }
}
