package `in`.jphe.storyvox.source.wikipedia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Section-splitter tests for [splitTopLevelSections] (#377).
 *
 * Parsoid HTML wraps each top-level article section in
 * `<section data-mw-section-id="N">...</section>`. Sub-sections (h3
 * and below) are wrapped in nested `<section>`s with their own
 * data-mw-section-id; the top-level splitter folds them into their
 * parent's html slice so the chapter count stays sensible.
 */
class WikipediaSectionSplitTest {

    @Test
    fun `lead and one top-level section produces two chapters`() {
        val html = """
            <section data-mw-section-id="0">
              <p>Lead paragraph for the article.</p>
            </section>
            <section data-mw-section-id="1">
              <h2 id="History">History</h2>
              <p>Stuff happened.</p>
            </section>
        """.trimIndent()

        val sections = splitTopLevelSections(html)
        assertEquals(2, sections.size)
        assertEquals("Introduction", sections[0].title)
        assertEquals("History", sections[1].title)
        assertTrue(sections[0].html.contains("Lead paragraph"))
        assertTrue(sections[1].html.contains("Stuff happened"))
    }

    @Test
    fun `nested sub-sections fold into parent chapter`() {
        // Wikipedia wraps h3 sub-sections in their own <section> tags;
        // the splitter should keep them inside the parent slice.
        val html = """
            <section data-mw-section-id="0"><p>Lead.</p></section>
            <section data-mw-section-id="1">
              <h2>Early life</h2>
              <p>Born in...</p>
              <section data-mw-section-id="2">
                <h3>Family</h3>
                <p>His mother was...</p>
              </section>
              <section data-mw-section-id="3">
                <h3>Education</h3>
                <p>Studied at...</p>
              </section>
            </section>
        """.trimIndent()

        val sections = splitTopLevelSections(html)
        // Two top-level sections (lead + "Early life"); nested h3s
        // stay inside "Early life".
        assertEquals(2, sections.size)
        assertEquals("Early life", sections[1].title)
        assertTrue(sections[1].html.contains("His mother was"))
        assertTrue(sections[1].html.contains("Studied at"))
    }

    @Test
    fun `references and external links are dropped`() {
        val html = """
            <section data-mw-section-id="0"><p>Lead.</p></section>
            <section data-mw-section-id="1"><h2>History</h2><p>Stuff.</p></section>
            <section data-mw-section-id="2"><h2>References</h2><p>[1] cite</p></section>
            <section data-mw-section-id="3"><h2>External links</h2><ul><li><a>x</a></li></ul></section>
            <section data-mw-section-id="4"><h2>See also</h2><p>more</p></section>
        """.trimIndent()

        val sections = splitTopLevelSections(html)
        val titles = sections.map { it.title }
        assertTrue(titles.contains("Introduction"))
        assertTrue(titles.contains("History"))
        assertFalse(titles.contains("References"))
        assertFalse(titles.contains("External links"))
        assertFalse(titles.contains("See also"))
    }

    @Test
    fun `article with no section tags becomes one introduction chapter`() {
        val html = "<p>An article body with no Parsoid sections.</p>"
        val sections = splitTopLevelSections(html)
        assertEquals(1, sections.size)
        assertEquals("Introduction", sections[0].title)
    }

    @Test
    fun `unclosed trailing section at EOF is skipped without throwing`() {
        // #1165: a truncated Parsoid response can end immediately after a
        // <section> open tag with no matching </section>. The old code
        // fell back to endIdx = html.length and called
        // substring(sliceStart, html.length - 10) with begin > end →
        // StringIndexOutOfBoundsException. The fix skips the broken slice.
        val html =
            """<section data-mw-section-id="0"><p>Lead paragraph.</p></section>""" +
                """<section data-mw-section-id="1">"""

        val sections = splitTopLevelSections(html)
        // Lead survives; the unclosed trailing section is dropped.
        assertEquals(1, sections.size)
        assertEquals("Introduction", sections[0].title)
        assertTrue(sections[0].html.contains("Lead paragraph"))
    }

    @Test
    fun `html ending immediately after a lone section open tag yields no sections`() {
        // Minimal crash repro from #1165: the only section is truncated.
        // Must return empty, not throw.
        val sections = splitTopLevelSections("""<section data-mw-section-id="1">""")
        assertTrue(sections.isEmpty())
    }

    @Test
    fun `cruft scrubber removes edit links and citation refs`() {
        val html = """
            <p>Marie Curie was a physicist.<sup id="cite_ref-1" class="reference">[1]</sup></p>
            <h2>Career<span class="mw-editsection">[<a>edit</a>]</span></h2>
            <p>She won two Nobel Prizes.</p>
        """.trimIndent()

        val cleaned = scrubWikipediaCruft(html)
        assertFalse(cleaned.contains("mw-editsection"))
        assertFalse(cleaned.contains("[1]"))
        assertFalse(cleaned.contains("reference"))
    }

    @Test
    fun `htmlToPlainText handles entities and block tags`() {
        val html = "<p>A &mdash; B&nbsp;C.</p><p>Second &amp; final.</p>"
        val plain = html.htmlToPlainText()
        assertTrue(plain.contains("A — B C."))
        assertTrue(plain.contains("Second & final."))
        assertFalse(plain.contains("&nbsp;"))
        assertFalse(plain.contains("&mdash;"))
    }

    @Test
    fun `fictionId roundtrip preserves underscored title`() {
        val id = wikipediaFictionId("Marie Curie")
        assertEquals("wikipedia:Marie_Curie", id)
        assertEquals("Marie_Curie", id.toArticleTitle())
    }
}
