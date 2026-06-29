package `in`.jphe.storyvox.data.source.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #1208 — the Gutenberg id helpers backing the companion matcher. */
class GutenbergRefTest {

    @Test
    fun `parses the ebook id from every catalog URL shape`() {
        assertEquals("1342", GutenbergRef.parseEbookIdFromUrl("https://www.gutenberg.org/ebooks/1342"))
        assertEquals(
            "1342",
            GutenbergRef.parseEbookIdFromUrl("http://www.gutenberg.org/files/1342/1342-h/1342-h.htm"),
        )
        assertEquals("1342", GutenbergRef.parseEbookIdFromUrl("https://www.gutenberg.org/etext/1342"))
        assertEquals(
            "1342",
            GutenbergRef.parseEbookIdFromUrl("https://www.gutenberg.org/cache/epub/1342/pg1342.txt"),
        )
        // www-less host + surrounding whitespace both tolerated.
        assertEquals("1342", GutenbergRef.parseEbookIdFromUrl("  https://gutenberg.org/ebooks/1342  "))
    }

    @Test
    fun `non-Gutenberg or idless URLs yield null`() {
        assertNull(GutenbergRef.parseEbookIdFromUrl(null))
        assertNull(GutenbergRef.parseEbookIdFromUrl(""))
        assertNull(GutenbergRef.parseEbookIdFromUrl("https://en.wikisource.org/wiki/Pride"))
        assertNull(GutenbergRef.parseEbookIdFromUrl("https://archive.org/details/prideprejudice"))
        assertNull(GutenbergRef.parseEbookIdFromUrl("https://www.gutenberg.org/about/")) // no numeric id
    }

    @Test
    fun `converts between ebook id and the gutenberg fiction id`() {
        assertEquals("gutenberg:1342", GutenbergRef.fictionIdFor("1342"))
        assertEquals("1342", GutenbergRef.ebookIdFromFictionId("gutenberg:1342"))
        assertNull(GutenbergRef.ebookIdFromFictionId("librivox:9999")) // wrong source
        assertNull(GutenbergRef.ebookIdFromFictionId("gutenberg:")) // empty after prefix
        assertNull(GutenbergRef.ebookIdFromFictionId("1342")) // bare id, no prefix
    }
}
