package `in`.jphe.storyvox.source.palace

import `in`.jphe.storyvox.data.text.htmlToPlainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1628 — Palace chapter `plainBody` now comes from the shared
 * `htmlToPlainText()` (core-data) instead of the module-local `stripTags`
 * regex (the last chapter-body stripper, twin of the one #1718 removed from
 * :source-gutenberg). Palace serves EPUB 3 spine documents, the same shape as
 * PG/Standard Ebooks, so the same contracts apply.
 *
 * Pins what the old stripper guarded — `<head>`/`<script>`/`<style>`/comment
 * removal with contents, case-insensitively — and the two things it got wrong
 * (the reason for #1628): paragraph breaks survive as `\n\n` (paragraph-nav
 * keys on them), and entities decode natively rather than leaking into TTS.
 */
class PalacePlainBodyTest {

    @Test
    fun `head script style and comments are stripped with their contents`() {
        val html = """
            <HTML><HEAD>
              <TITLE>Ch. 1</TITLE>
              <STYLE>body { color: black; }</STYLE>
              <SCRIPT>alert('x');</SCRIPT>
            </HEAD><BODY>
              <!-- editor note -->
              <h1>Chapter One</h1>
              <p>The visible prose.</p>
            </BODY></HTML>
        """.trimIndent()

        val plain = html.htmlToPlainText()

        assertTrue(plain.contains("Chapter One"))
        assertTrue(plain.contains("The visible prose."))
        assertFalse("style leaked: $plain", plain.contains("color:"))
        assertFalse("script leaked: $plain", plain.contains("alert"))
        assertFalse("comment leaked: $plain", plain.contains("editor note"))
        assertFalse("title leaked: $plain", plain.contains("Ch. 1"))
    }

    @Test
    fun `paragraph breaks survive as blank lines`() {
        assertEquals("One.\n\nTwo.", "<p>One.</p><p>Two.</p>".htmlToPlainText())
    }

    @Test
    fun `entities decode natively`() {
        assertEquals(
            "It’s a “quote” & an em—dash.",
            "<p>It&rsquo;s a &ldquo;quote&rdquo; &amp; an em&mdash;dash.</p>".htmlToPlainText(),
        )
    }

    @Test
    fun `empty and tag-only input return empty string`() {
        assertEquals("", "".htmlToPlainText())
        assertEquals("", "<head><title>only</title></head>".htmlToPlainText())
    }
}
