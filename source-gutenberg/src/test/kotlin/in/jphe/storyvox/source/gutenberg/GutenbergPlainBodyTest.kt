package `in`.jphe.storyvox.source.gutenberg

import `in`.jphe.storyvox.data.text.htmlToPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1628 — Gutenberg's chapter `plainBody` now comes from the shared
 * `htmlToPlainText()` (core-data) instead of the module-local `stripTags`
 * regex. These tests re-pin, *against the shared util*, the contracts the
 * old `StripTagsTest` guarded for this source:
 *
 *  - Issue #442: `<head>` / `<script>` / `<style>` / comments are removed
 *    **with their contents** (a CSS block narrated by Piper was the 0:00
 *    buffering hang), case-insensitively.
 *  - Issue #733: a tag-only / cover-only spine entry yields `""`, so
 *    [withoutEmptySpineItems] can drop it.
 *
 * …and pins the two things the old stripper got *wrong* (the reason #1628
 * exists): paragraph breaks are preserved as `\n\n` (paragraph-nav keys on
 * them; `stripTags` flattened every chapter to one line), and HTML entities
 * are decoded natively (`stripTags` decoded none, so `&rsquo;` was narrated).
 */
class GutenbergPlainBodyTest {

    @Test
    fun `head block contents are stripped not preserved`() {
        val html = """
            <html>
              <head>
                <title>Frankenstein</title>
                <style>body { color: black; font-family: serif; }</style>
                <meta name="generator" content="Project Gutenberg"/>
              </head>
              <body>
                <h1>Letter I</h1>
                <p>To Mrs. Saville, England.</p>
              </body>
            </html>
        """.trimIndent()

        val plain = html.htmlToPlainText()

        assertTrue("Body text 'Letter I' missing: $plain", plain.contains("Letter I"))
        assertTrue(plain.contains("To Mrs. Saville, England."))
        assertFalse("Title leaked from <head>: $plain", plain.contains("Frankenstein"))
        assertFalse("CSS leaked (#442 regression): $plain", plain.contains("color:"))
        assertFalse("Generator meta leaked: $plain", plain.contains("Project Gutenberg"))
    }

    @Test
    fun `script and style blocks are stripped case-insensitively`() {
        val html = """
            <HTML>
              <HEAD>
                <STYLE TYPE="text/css">p { margin: 0; }</STYLE>
                <SCRIPT>alert('hi');</SCRIPT>
              </HEAD>
              <BODY>
                <P>Real text.</P>
              </BODY>
            </HTML>
        """.trimIndent()

        val plain = html.htmlToPlainText()

        assertTrue(plain.contains("Real text."))
        assertFalse(plain.contains("margin"))
        assertFalse(plain.contains("alert"))
    }

    @Test
    fun `HTML comments are stripped`() {
        val html = "<body><!-- editor note: see SE issue --><p>The text.</p></body>"
        val plain = html.htmlToPlainText()
        assertTrue(plain.contains("The text."))
        assertFalse(plain.contains("editor note"))
    }

    @Test
    fun `body-only document keeps every paragraph`() {
        val html = """
            <body>
              <h1>Chapter 1</h1>
              <p>I was so much pleased.</p>
              <p>I shall depart from Russia.</p>
            </body>
        """.trimIndent()

        val plain = html.htmlToPlainText()

        assertTrue(plain.contains("Chapter 1"))
        assertTrue(plain.contains("I was so much pleased."))
        assertTrue(plain.contains("I shall depart from Russia."))
    }

    /** The #1628 fix proper: the old stripper asserted `"One. Two."` here. */
    @Test
    fun `paragraph breaks survive as blank lines for paragraph navigation`() {
        val html = "<p>One.</p>\n\n<p>Two.</p>"
        assertEquals("One.\n\nTwo.", html.htmlToPlainText())
    }

    /** The old stripper decoded no entities, so curly quotes reached TTS as text. */
    @Test
    fun `entities are decoded natively`() {
        val html = "<p>It&rsquo;s Mrs. Saville&#8217;s letter &amp; Walton&#x2019;s reply.</p>"
        assertEquals("It’s Mrs. Saville’s letter & Walton’s reply.", html.htmlToPlainText())
    }

    @Test
    fun `empty input returns empty string`() {
        assertEquals("", "".htmlToPlainText())
    }

    @Test
    fun `tag-only input returns empty string`() {
        // Some PG spine entries are pure metadata pages with no visible body
        // content; [withoutEmptySpineItems] relies on this being exactly "".
        assertEquals("", "<head><title>only</title></head>".htmlToPlainText())
    }

    @Test
    fun `cover-only spine entry is empty`() {
        val coverHtml = """
            <!DOCTYPE html><html><head><title>"Cover"</title></head>
            <body class="x-ebookmaker-coverpage">
              <div class="x-ebookmaker-cover">
                <svg viewBox="0 0 1824 2726"><image xlink:href="cover.jpg"/></svg>
              </div>
            </body></html>
        """.trimIndent()
        assertEquals("", coverHtml.htmlToPlainText())
    }
}
