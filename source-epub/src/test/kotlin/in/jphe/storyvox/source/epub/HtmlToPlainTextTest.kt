package `in`.jphe.storyvox.source.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1623 — real EPUB chapters used to collapse to one run-on paragraph.
 *
 * A real EPUB `htmlBody` is the ENTIRE chapter XHTML document, and the old
 * naive stripper (`<tag>`->" ", `\s+`->" ") flattened the whole chapter into
 * one line, leaked `<head>`/`<style>`/`<script>` text into the prose, and
 * never decoded entities. The reader, the TTS `SentenceChunker`, and
 * paragraph-level navigation all consume `plainBody`, and paragraph nav keys
 * on blank lines (`\n\n`) — so the collapse killed paragraph structure.
 *
 * [String.htmlToPlainText] replaces that stripper with a jsoup DOM parse
 * that drops non-content nodes, inserts `\n\n` after each block element and
 * `\n` for `<br>`, and decodes entities natively.
 *
 * These are pure-function tests over realistic full-XHTML-document fixtures —
 * i.e. real EPUB chapter HTML, not plaintext. (An end-to-end pass through
 * [`in`.jphe.storyvox.source.epub.parse.EpubParser] would drag Robolectric
 * in, since the parser reads the OPF via `android.util.Xml`; the transform
 * under test is HTML->text, which this covers directly.)
 */
class HtmlToPlainTextTest {

    /** Blank-line-separated paragraph count — the signal paragraph-nav uses. */
    private fun paragraphCount(body: String): Int =
        body.split(Regex("\n[ \t]*\n")).count { it.isNotBlank() }

    @Test
    fun `full XHTML document drops head, style, and title text`() {
        val html = """
            <html><head><title>Book Title</title>
            <style>.c{color:red}</style></head>
            <body><p>Hello world.</p></body></html>
        """.trimIndent()
        val out = html.htmlToPlainText()

        assertEquals("Hello world.", out)
        assertFalse("title text leaked", out.contains("Book Title"))
        assertFalse("css leaked", out.contains("color"))
    }

    @Test
    fun `script content is dropped`() {
        val out = "<body><script>alert('boom')</script><p>Real content.</p></body>"
            .htmlToPlainText()
        assertFalse(out.contains("alert"))
        assertFalse(out.contains("boom"))
        assertTrue(out.contains("Real content."))
    }

    @Test
    fun `consecutive paragraphs are separated by a blank line`() {
        val out = "<body><p>First paragraph.</p><p>Second paragraph.</p></body>"
            .htmlToPlainText()
        assertEquals("First paragraph.\n\nSecond paragraph.", out)
        assertEquals(2, paragraphCount(out))
    }

    @Test
    fun `br becomes a single soft line break, not a paragraph break`() {
        val out = "<body><p>Line one<br>Line two</p></body>".htmlToPlainText()
        assertEquals("Line one\nLine two", out)
    }

    @Test
    fun `headings are separated from the following paragraph`() {
        val out = "<body><h1>Chapter One</h1><p>The story begins.</p></body>"
            .htmlToPlainText()
        assertEquals("Chapter One\n\nThe story begins.", out)
    }

    @Test
    fun `nested blocks do not stack up extra blank lines`() {
        val out = "<body><div><p>Inside.</p></div><p>After.</p></body>".htmlToPlainText()
        // <p> close + <div> close would emit two blank lines; capped to one.
        assertEquals("Inside.\n\nAfter.", out)
    }

    @Test
    fun `inline tags stay on the same line`() {
        val out = "<body><p>a <em>b</em> c <strong>d</strong></p></body>".htmlToPlainText()
        assertFalse("inline tags must not introduce line breaks", out.contains("\n"))
        assertTrue(out.contains("b"))
        assertTrue(out.contains("d"))
    }

    @Test
    fun `named and numeric entities are decoded, not leaked`() {
        val out = "<body><p>Tom &amp; Jerry &rsquo;quote&rsquo; &mdash; end&hellip;</p></body>"
            .htmlToPlainText()
        assertTrue(out.contains("Tom & Jerry"))
        assertTrue("right single quote decoded", out.contains("’"))
        assertTrue("em-dash decoded", out.contains("—"))
        assertTrue("ellipsis decoded", out.contains("…"))
        assertFalse(out.contains("&amp;"))
        assertFalse(out.contains("&rsquo;"))
        assertFalse(out.contains("&mdash;"))
        assertFalse(out.contains("&hellip;"))
    }

    @Test
    fun `blank and empty inputs yield empty string`() {
        assertEquals("", "".htmlToPlainText())
        assertEquals("", "   \n\t ".htmlToPlainText())
        assertEquals("", "<body></body>".htmlToPlainText())
    }

    @Test
    fun `single paragraph produces no spurious line breaks`() {
        val out = "<body><p>Just one paragraph here.</p></body>".htmlToPlainText()
        assertEquals("Just one paragraph here.", out)
        assertFalse(out.contains("\n"))
    }

    @Test
    fun `realistic EPUB chapter keeps paragraphs, decodes entities, drops style`() {
        val html = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml"><head>
            <title>Ch 1</title>
            <link rel="stylesheet" type="text/css" href="../css/style.css"/>
            <style>p{text-indent:1em}</style>
            </head>
            <body>
            <h1>Chapter 1</h1>
            <p>It was a bright cold day in April, and the clocks were striking thirteen.</p>
            <p>Winston Smith, his chin nuzzled into his breast&#8217;s warmth, slipped
            quickly through the glass doors.</p>
            </body></html>
        """.trimIndent()
        val out = html.htmlToPlainText()

        assertEquals("three paragraphs preserved", 3, paragraphCount(out))
        assertTrue(out.contains("Chapter 1"))
        assertTrue("entity decoded to curly apostrophe", out.contains("breast’s"))
        // Intra-paragraph source newline is HTML whitespace -> single space.
        assertTrue(out.contains("slipped quickly"))
        assertFalse("css never reaches prose", out.contains("text-indent"))
        assertFalse("head title never reaches prose", out.contains("Ch 1"))
        assertFalse(out.contains("&#8217;"))
    }
}
