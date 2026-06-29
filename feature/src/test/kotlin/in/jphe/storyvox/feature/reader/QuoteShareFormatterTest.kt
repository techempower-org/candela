package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1234 — pins the exact text a reader shares / copies from a highlight.
 * Pure (no Compose / Android), mirroring [ReaderHighlightSelectionTest] and
 * core-data's `AnnotationExportFormatterTest`: the dispatch (Intent / clipboard)
 * isn't unit-testable here, but the string is the part that matters and it's
 * worth locking down so attribution can't silently regress.
 */
class QuoteShareFormatterTest {

    @Test
    fun `full attribution renders quote, dash line, and brand signature`() {
        val out = QuoteShareFormatter.format(
            quote = "The quick brown fox.",
            author = "Jane Doe",
            bookTitle = "The Forest",
            chapterTitle = "Chapter 3",
        )
        assertEquals(
            "“The quick brown fox.”\n\n— Jane Doe, The Forest, Chapter 3\nvia Candela",
            out,
        )
    }

    @Test
    fun `quote is wrapped in curly quotes`() {
        val out = QuoteShareFormatter.format("hi", "A", "B", "C")
        assertTrue(out.startsWith("“hi”"))
        // No straight ASCII double-quote leaks into the shared text.
        assertFalse(out.contains('"'))
    }

    @Test
    fun `brand signature is always the last line`() {
        val out = QuoteShareFormatter.format("hi", "A", "B", "C")
        assertEquals("via Candela", out.lines().last())
    }

    @Test
    fun `blank author is dropped without a dangling comma`() {
        val out = QuoteShareFormatter.format(
            quote = "Hi.",
            author = "",
            bookTitle = "The Forest",
            chapterTitle = "Chapter 3",
        )
        assertEquals("“Hi.”\n\n— The Forest, Chapter 3\nvia Candela", out)
    }

    @Test
    fun `blank chapter is dropped from the attribution`() {
        val out = QuoteShareFormatter.format(
            quote = "Hi.",
            author = "Jane Doe",
            bookTitle = "The Forest",
            chapterTitle = "   ",
        )
        assertEquals("“Hi.”\n\n— Jane Doe, The Forest\nvia Candela", out)
    }

    @Test
    fun `all-blank metadata omits the attribution line entirely`() {
        val out = QuoteShareFormatter.format(
            quote = "Hi.",
            author = "",
            bookTitle = "",
            chapterTitle = "",
        )
        // No dash line, no blank gap — just the quote and the signature.
        assertEquals("“Hi.”\nvia Candela", out)
    }

    @Test
    fun `multi-line selection collapses internal whitespace to single spaces`() {
        val out = QuoteShareFormatter.format(
            quote = "  line one\n\n  line two\t",
            author = "Jane Doe",
            bookTitle = "The Forest",
            chapterTitle = "Chapter 3",
        )
        assertEquals(
            "“line one line two”\n\n— Jane Doe, The Forest, Chapter 3\nvia Candela",
            out,
        )
    }
}
