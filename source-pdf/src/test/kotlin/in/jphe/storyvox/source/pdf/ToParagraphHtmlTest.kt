package `in`.jphe.storyvox.source.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #996 — tests for [toParagraphHtml], which wraps extracted PDF
 * plain text into minimal paragraph HTML for the reader's htmlBody
 * field while HTML-escaping stray markup characters.
 */
class ToParagraphHtmlTest {

    @Test
    fun `blank-line separated text becomes separate paragraphs`() {
        val html = "First para.\n\nSecond para.".toParagraphHtml()
        assertEquals("<p>First para.</p>\n<p>Second para.</p>", html)
    }

    @Test
    fun `markup characters are escaped`() {
        val html = "a < b & c > d".toParagraphHtml()
        assertTrue(html.contains("&lt;"))
        assertTrue(html.contains("&amp;"))
        assertTrue(html.contains("&gt;"))
        // No raw angle bracket from the source text leaked into the body.
        assertFalse(html.contains("a < b"))
    }

    @Test
    fun `blank input yields empty string`() {
        assertEquals("", "".toParagraphHtml())
        assertEquals("", "   \n\n  ".toParagraphHtml())
    }
}
