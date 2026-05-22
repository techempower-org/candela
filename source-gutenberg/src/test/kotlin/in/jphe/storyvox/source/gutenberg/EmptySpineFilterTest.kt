package `in`.jphe.storyvox.source.gutenberg

import `in`.jphe.storyvox.source.epub.parse.EpubBook
import `in`.jphe.storyvox.source.epub.parse.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Issue #733 — playback fails on real Gutenberg books because the
 * first spine entry is the `coverpage-wrapper` HTML containing only an
 * SVG `<image>` reference to the cover JPEG. Its [stripTags] yields an
 * empty string, so the chapter row's `plainBody` lands empty in the
 * DB. The render worker logs `PRERENDER-SKIP-NOTEXT`; the foreground
 * `loadAndPlay` path returns null from `chapterRepo.getChapter()` and
 * surfaces `ChapterFetchFailed`.
 *
 * [withoutEmptySpineItems] drops those entries so the chapter list
 * starts at the first spine item with audible text. The remaining
 * chapters keep their original [EpubChapter.index] so the
 * `gutenberg:N::SPINE_INDEX` chapter id encoding is stable across the
 * filter; downstream lookups in [GutenbergSource.chapter] match by
 * `it.index == idx` (not list position), so gaps in the index sequence
 * are safe.
 */
class EmptySpineFilterTest {

    @Test
    fun `drops cover SVG entry whose stripTags is empty`() {
        // Real-shape coverpage-wrapper from a PG EPUB3 build — body
        // contains only an SVG <image>; no visible text.
        val coverHtml = """
            <!DOCTYPE html><html><head><title>"Cover"</title></head>
            <body class="x-ebookmaker-coverpage">
              <div class="x-ebookmaker-cover">
                <svg viewBox="0 0 1824 2726"><image xlink:href="cover.jpg"/></svg>
              </div>
            </body></html>
        """.trimIndent()

        val pgHeaderHtml = """
            <html><head><title>Frankenstein</title></head>
            <body><div class="pgheader"><h2>The Project Gutenberg eBook of Frankenstein</h2>
            <p>This eBook is for the use of anyone anywhere in the United States.</p></div></body></html>
        """.trimIndent()

        val realChapterHtml = """
            <html><head><title>Frankenstein</title></head>
            <body><h2>Letter 1</h2><p>To Mrs. Saville, England.</p>
            <p>You will rejoice to hear that no disaster has accompanied the commencement.</p></body></html>
        """.trimIndent()

        val book = EpubBook(
            title = "Frankenstein",
            author = "Mary Wollstonecraft Shelley",
            chapters = listOf(
                EpubChapter(id = "coverpage-wrapper", title = "Chapter 1", index = 0, htmlBody = coverHtml),
                EpubChapter(id = "pg-header", title = "Chapter 2", index = 1, htmlBody = pgHeaderHtml),
                EpubChapter(id = "item4", title = "Chapter 3", index = 2, htmlBody = realChapterHtml),
            ),
        )

        val filtered = book.withoutEmptySpineItems()

        // Cover is dropped.
        assertEquals(2, filtered.chapters.size)
        assertFalse(
            "Cover SVG entry should be filtered out",
            filtered.chapters.any { it.id == "coverpage-wrapper" },
        )

        // pg-header and the real chapter survive, with their original
        // spine indexes intact — chapter ids stay stable.
        assertEquals(1, filtered.chapters[0].index)
        assertEquals("pg-header", filtered.chapters[0].id)
        assertEquals(2, filtered.chapters[1].index)
        assertEquals("item4", filtered.chapters[1].id)
    }

    @Test
    fun `preserves index gaps so chapter id encoding stays stable`() {
        // Mixed-shape spine: a cover, then real content, then an
        // image-only mid-book inset, then more real content. The mid-
        // book inset is rare in PG but easy to reproduce on Standard
        // Ebooks-derived builds.
        val empty = """<html><body><div><img src="x.jpg"/></div></body></html>""".trimIndent()
        val real = """<html><body><p>The text.</p></body></html>""".trimIndent()
        val book = EpubBook(
            title = "T",
            author = "A",
            chapters = listOf(
                EpubChapter("c0", "C0", 0, empty),
                EpubChapter("c1", "C1", 1, real),
                EpubChapter("c2", "C2", 2, empty),
                EpubChapter("c3", "C3", 3, real),
            ),
        )

        val filtered = book.withoutEmptySpineItems()

        // Indexes 1 and 3 survive with a gap at 2 — that's load-bearing.
        // chapterIdFor(fictionId, ch.index) builds the chapter id from
        // EpubChapter.index, and GutenbergSource.chapter() looks up via
        // firstOrNull { it.index == idx } rather than list position, so
        // the gap doesn't break navigation.
        assertEquals(2, filtered.chapters.size)
        assertEquals(1, filtered.chapters[0].index)
        assertEquals(3, filtered.chapters[1].index)
        assertNotNull(filtered.chapters.firstOrNull { it.index == 1 })
        assertNotNull(filtered.chapters.firstOrNull { it.index == 3 })
    }

    @Test
    fun `book with no empty entries is returned unchanged`() {
        val real = """<html><body><p>Text.</p></body></html>""".trimIndent()
        val book = EpubBook(
            title = "T",
            author = "A",
            chapters = listOf(
                EpubChapter("c0", "C0", 0, real),
                EpubChapter("c1", "C1", 1, real),
            ),
        )

        val filtered = book.withoutEmptySpineItems()

        assertEquals(2, filtered.chapters.size)
        assertEquals(0, filtered.chapters[0].index)
        assertEquals(1, filtered.chapters[1].index)
    }

    @Test
    fun `style-only spine entry is dropped because stripTags removes style contents`() {
        // The #442 fix removes the contents of <style> blocks. A spine
        // entry that contains only `<head>` + `<body><style>…</style></body>`
        // (rare but seen on a couple of pre-2010 Gutenberg builds where
        // the converter dropped a full stylesheet into the body) yields
        // an empty stripTags — and would silently break playback the
        // same way the cover SVG does.
        val styleOnly = """
            <html><head><title>x</title></head>
            <body><style>body { color: black; }</style></body></html>
        """.trimIndent()
        val real = """<html><body><p>Letter 1. To Mrs. Saville.</p></body></html>""".trimIndent()
        val book = EpubBook(
            title = "T",
            author = "A",
            chapters = listOf(
                EpubChapter("c0", "C0", 0, styleOnly),
                EpubChapter("c1", "C1", 1, real),
            ),
        )

        val filtered = book.withoutEmptySpineItems()

        assertEquals(1, filtered.chapters.size)
        assertEquals(1, filtered.chapters[0].index)
    }
}
