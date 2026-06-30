package `in`.jphe.storyvox.source.pdf

import `in`.jphe.storyvox.source.pdf.parse.PdfChapterBuilder
import `in`.jphe.storyvox.source.pdf.parse.PdfPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #996 — pure-logic tests for [PdfChapterBuilder]. No Robolectric:
 * the builder is Android-free by design so chapter grouping, heading
 * detection, and blank-page handling can be exercised directly.
 */
class PdfChapterBuilderTest {

    private fun pages(vararg texts: String): List<PdfPage> =
        texts.mapIndexed { i, t -> PdfPage(index = i, text = t) }

    @Test
    fun `empty pages yields no chapters`() {
        assertTrue(PdfChapterBuilder.build(emptyList()).isEmpty())
    }

    @Test
    fun `no headings falls back to page-batch grouping`() {
        val input = pages(
            "page one body", "page two body", "page three body",
            "page four body", "page five body", "page six body",
        )
        val chapters = PdfChapterBuilder.build(input, pagesPerChapter = 5)
        // 6 pages, 5 per chapter -> 2 chapters.
        assertEquals(2, chapters.size)
        assertEquals(0, chapters[0].firstPageIndex)
        assertEquals(5, chapters[1].firstPageIndex)
        assertEquals("Pages 1–5", chapters[0].title)
        assertEquals("Page 6", chapters[1].title)
        // index is contiguous
        assertEquals(0, chapters[0].index)
        assertEquals(1, chapters[1].index)
    }

    @Test
    fun `heading on a page starts a new chapter and consumes following pages`() {
        val input = pages(
            "Chapter 1\nThe beginning of the tale.",
            "continued narration without a heading",
            "Chapter 2\nThe middle of the tale.",
            "more continued narration",
        )
        val chapters = PdfChapterBuilder.build(input)
        assertEquals(2, chapters.size)
        assertEquals("Chapter 1", chapters[0].title)
        assertEquals("Chapter 2", chapters[1].title)
        assertTrue(chapters[0].plainBody.contains("The beginning"))
        assertTrue(chapters[0].plainBody.contains("continued narration without"))
        assertTrue(chapters[1].plainBody.contains("more continued narration"))
    }

    @Test
    fun `numbered heading form is detected`() {
        assertTrue(PdfChapterBuilder.hasHeading("1. Introduction"))
        assertTrue(PdfChapterBuilder.hasHeading("CHAPTER IV"))
        assertTrue(PdfChapterBuilder.hasHeading("Part Two"))
        assertTrue(PdfChapterBuilder.hasHeading("Section 3"))
        assertFalse(PdfChapterBuilder.hasHeading("just a regular sentence of body text"))
        assertFalse(PdfChapterBuilder.hasHeading(""))
    }

    @Test
    fun `heading over-firing on every page falls back to batch grouping`() {
        // A running header that looks heading-like on every page should
        // NOT produce one chapter per page — that's the over-fire guard.
        val input = pages(
            "Chapter Notes\nbody a",
            "Chapter Notes\nbody b",
            "Chapter Notes\nbody c",
            "Chapter Notes\nbody d",
            "Chapter Notes\nbody e",
        )
        val chapters = PdfChapterBuilder.build(input, pagesPerChapter = 5)
        // headingFraction == 1.0 > 0.6 -> batch grouping -> single chapter.
        assertEquals(1, chapters.size)
    }

    @Test
    fun `blank image-only pages are dropped from bodies but chapters with text survive`() {
        val input = pages(
            "real born-digital text",
            "", // scanned page, no text layer, OCR unavailable (Phase 1)
            "more real text",
        )
        val chapters = PdfChapterBuilder.build(input, pagesPerChapter = 5)
        assertEquals(1, chapters.size)
        val body = chapters[0].plainBody
        assertTrue(body.contains("real born-digital text"))
        assertTrue(body.contains("more real text"))
        // No empty leading/trailing artifacts from the blank page.
        assertFalse(body.contains("\n\n\n"))
    }

    @Test
    fun `chapter made entirely of blank pages is removed`() {
        // All-blank input (e.g. a fully-scanned PDF with no OCR) yields
        // no narratable chapters rather than empty placeholders.
        val input = pages("", "", "")
        val chapters = PdfChapterBuilder.build(input, pagesPerChapter = 5)
        assertTrue(chapters.isEmpty())
    }

    @Test
    fun `page bodies are reflowed so wrapped lines do not survive as breaks`() {
        // #1428 — a single page whose paragraph PdfBox split across visual
        // lines must come back as flowing text, with the real (blank-line)
        // paragraph break preserved.
        val input = pages(
            "This sentence was wrapped\nacross three visual lines\nby the PDF layout.\n\nThis is a separate paragraph.",
        )
        val chapters = PdfChapterBuilder.build(input, pagesPerChapter = 5)
        assertEquals(1, chapters.size)
        assertEquals(
            "This sentence was wrapped across three visual lines by the PDF layout.\n\n" +
                "This is a separate paragraph.",
            chapters[0].plainBody,
        )
    }

    @Test
    fun `chapter ids are stable and unique by first page`() {
        // 2 heading pages out of 4 (fraction 0.5 <= 0.6) -> heading split.
        val input = pages(
            "Chapter 1\na", "b",
            "Chapter 2\nc", "d",
        )
        val chapters = PdfChapterBuilder.build(input)
        assertEquals(2, chapters.size)
        assertEquals("page-0", chapters[0].id)
        assertEquals("page-2", chapters[1].id)
    }
}
