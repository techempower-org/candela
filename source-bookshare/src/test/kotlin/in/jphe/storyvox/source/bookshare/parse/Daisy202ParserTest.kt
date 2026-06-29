package `in`.jphe.storyvox.source.bookshare.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #1293 — DAISY 2.02 parsing, exercised against a **real, public-domain**
 * package: the WBU "WIPO Treaty for the Visually Impaired" text-only DAISY 2.02
 * book from the DAISY Consortium sample-files archive (`dl.daisy.org/samples/
 * 202text-only/`), vendored verbatim at `src/test/resources/daisy202/`. Using a
 * real package (rather than synthetic fixtures) validates the ncc → SMIL →
 * content-document resolution against real-world markup. Robolectric-backed
 * because [Daisy202Parser] uses `android.util.Xml`.
 */
@RunWith(RobolectricTestRunner::class)
class Daisy202ParserTest {

    private fun sampleBytes(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/daisy202/wipo-treaty-d202.zip")) {
            "vendored DAISY 2.02 sample missing from test resources"
        }.use { it.readBytes() }

    private fun parse(): DaisyBook = Daisy202Parser.parseFromBytes(sampleBytes())

    @Test
    fun `reads dc title and author from ncc`() {
        val book = parse()
        assertEquals("WIPO Treaty for the Visually Impaired", book.title)
        assertEquals("WBU", book.author)
    }

    @Test
    fun `extracts every ncc h1 as a chapter, skipping page spans`() {
        val book = parse()
        // ncc has 25 toc items: 23 h1 headings + 2 page-normal spans (skipped).
        assertEquals(23, book.chapters.size)
        val titles = book.chapters.map { it.title }
        assertTrue(titles.contains("Preamble"))
        assertTrue(titles.contains("Article 1. Purpose"))
        assertTrue(titles.contains("Article 20. Monitoring and Implementation"))
    }

    @Test
    fun `extracts chapter body prose from the content document`() {
        val preamble = parse().chapters.first { it.title == "Preamble" }
        assertTrue("preamble body should not be blank", preamble.plainBody.isNotBlank())
        assertTrue(
            "missing expected preamble prose; got: ${preamble.plainBody.take(160)}",
            preamble.plainBody.contains("Contracting Parties"),
        )
        assertTrue("html body should wrap paragraphs", preamble.htmlBody.contains("<p>"))
    }

    @Test
    fun `drops page-number markers from narrated body`() {
        // The Preamble's content range contains the "2" page-normal marker
        // (class="page-*"); it must not surface as a narrated paragraph.
        val preamble = parse().chapters.first { it.title == "Preamble" }
        assertFalse(
            "page-number marker leaked into the body",
            preamble.plainBody.split("\n\n").any { it.trim() == "2" },
        )
    }
}
