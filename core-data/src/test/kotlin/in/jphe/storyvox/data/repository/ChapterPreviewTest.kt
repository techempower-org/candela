package `in`.jphe.storyvox.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1189 — unit coverage for the chapter content-preview cleaner. Pure
 * Kotlin (no Android `Html.fromHtml`) so it runs under the project's
 * JUnit-only / no-Robolectric harness.
 */
class ChapterPreviewTest {

    @Test fun `null or blank input yields null`() {
        assertNull(chapterPreviewText(null))
        assertNull(chapterPreviewText(""))
        assertNull(chapterPreviewText("    \n\t  "))
    }

    @Test fun `markup-only input yields null`() {
        assertNull(chapterPreviewText("<p></p>"))
        assertNull(chapterPreviewText("<div><span></span></div>"))
    }

    @Test fun `strips residual HTML tags`() {
        assertEquals(
            "Hello world",
            chapterPreviewText("<p>Hello <b>world</b></p>"),
        )
    }

    @Test fun `decodes common named HTML entities`() {
        assertEquals(
            "Jack & Jill said \"hi\"",
            chapterPreviewText("Jack &amp; Jill said &quot;hi&quot;"),
        )
    }

    @Test fun `decodes numeric and hex HTML entities`() {
        // &#39; = apostrophe, &#x2014; = em dash
        assertEquals(
            "it's — done",
            chapterPreviewText("it&#39;s &#x2014; done"),
        )
    }

    @Test fun `collapses runs of whitespace into single spaces`() {
        assertEquals(
            "Line one. Line two.",
            chapterPreviewText("Line one.\n\n\tLine two."),
        )
    }

    @Test fun `strips a redundant leading chapter-number prefix`() {
        assertEquals(
            "The wind howled through the pass.",
            chapterPreviewText("Chapter 1: The wind howled through the pass."),
        )
        assertEquals(
            "Snow fell.",
            chapterPreviewText("Ch. 12 — Snow fell."),
        )
    }

    @Test fun `body that is only its heading falls back to the heading`() {
        // Stripping the prefix would leave nothing; prefer showing the heading
        // over emitting a blank (which the caller would drop).
        assertEquals("Chapter 5", chapterPreviewText("Chapter 5"))
    }

    @Test fun `short text is returned verbatim without an ellipsis`() {
        val text = "A short opening line."
        assertEquals(text, chapterPreviewText(text, maxChars = 100))
    }

    @Test fun `long text is truncated on a word boundary with an ellipsis`() {
        // 12-char budget over "The quick brown fox jumps" → last word boundary
        // inside the window is after "quick".
        assertEquals("The quick…", chapterPreviewText("The quick brown fox jumps", maxChars = 12))
    }

    @Test fun `a single oversized token is hard-cut rather than collapsed to nothing`() {
        val result = chapterPreviewText("a".repeat(40), maxChars = 10)!!
        assertTrue("should end with ellipsis", result.endsWith("…"))
        // 10 chars of body + the ellipsis glyph.
        assertEquals(11, result.length)
    }

    @Test fun `trailing punctuation is trimmed before the ellipsis`() {
        // Window cut lands right after a comma → comma dropped, ellipsis added.
        assertEquals("Hello…", chapterPreviewText("Hello, world and then some more", maxChars = 7))
    }
}
