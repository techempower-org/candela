package `in`.jphe.storyvox.source.standardebooks

import `in`.jphe.storyvox.source.standardebooks.net.SeHtmlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #375 — pins the regex extractors in [SeHtmlParser] against the
 * actual schema.org-marked-up HTML that standardebooks.org serves from
 * `/ebooks?view=list`. The fixtures here are trimmed but otherwise
 * verbatim from real responses; SE's templating is server-side and
 * stable, so a parser failure here is the right signal that the page
 * markup shifted upstream.
 */
class SeHtmlParserTest {

    /**
     * Two-entry listing fragment with the pagination footer. Exercises
     * title / author / cover / tags extraction + hasNext detection.
     */
    private val twoEntryListing = """
        <ol class="ebooks-list list">
            <li typeof="schema:Book" about="/ebooks/dornford-yates/perishable-goods">
                <div class="thumbnail-container" aria-hidden="true">
                    <a href="/ebooks/dornford-yates/perishable-goods" tabindex="-1" property="schema:url">
                        <picture>
                            <img src="/images/covers/dornford-yates_perishable-goods-48e72061-cover@2x.jpg"
                                 alt="" property="schema:image" height="335" width="224"/>
                        </picture>
                    </a>
                </div>
                <p><a href="/ebooks/dornford-yates/perishable-goods" property="schema:url">
                    <span property="schema:name">Perishable Goods</span></a></p>
                <div>
                    <p class="author"><a href="/ebooks/dornford-yates">Dornford Yates</a></p>
                </div>
                <div class="details">
                    <p>65,646 words • 80.11 reading ease</p>
                    <ul class="tags">
                        <li><a href="/subjects/adventure">Adventure</a></li>
                        <li><a href="/subjects/fiction">Fiction</a></li>
                    </ul>
                </div>
            </li>
            <li typeof="schema:Book" about="/ebooks/joshua-slocum/sailing-alone-around-the-world">
                <div class="thumbnail-container" aria-hidden="true">
                    <a href="/ebooks/joshua-slocum/sailing-alone-around-the-world" tabindex="-1" property="schema:url">
                        <picture>
                            <img src="/images/covers/joshua-slocum_sailing-alone-around-the-world-94f3c8ca-cover@2x.jpg"
                                 alt="" property="schema:image" height="335" width="224"/>
                        </picture>
                    </a>
                </div>
                <p><a href="/ebooks/joshua-slocum/sailing-alone-around-the-world" property="schema:url">
                    <span property="schema:name">Sailing Alone Around the World</span></a></p>
                <div>
                    <p class="author"><a href="/ebooks/joshua-slocum">Joshua Slocum</a></p>
                </div>
                <div class="details">
                    <p>68,722 words • 73.81 reading ease</p>
                    <ul class="tags">
                        <li><a href="/subjects/adventure">Adventure</a></li>
                        <li><a href="/subjects/memoir">Memoir</a></li>
                        <li><a href="/subjects/nonfiction">Nonfiction</a></li>
                        <li><a href="/subjects/travel">Travel</a></li>
                    </ul>
                </div>
            </li>
        </ol>
        <nav class="pagination">
            <a aria-disabled="true">Back</a>
            <ol>
                <li><a aria-current="page" href="#">1</a></li>
                <li><a href="/ebooks?page=2&amp;view=list">2</a></li>
            </ol>
            <a href="/ebooks?page=2&amp;view=list" rel="next">Next</a>
        </nav>
    """.trimIndent()

    @Test
    fun `parseListPage extracts both entries with full metadata`() {
        val page = SeHtmlParser.parseListPage(twoEntryListing)
        assertEquals(2, page.results.size)

        val first = page.results[0]
        assertEquals("dornford-yates", first.authorSlug)
        assertEquals("perishable-goods", first.bookSlug)
        assertEquals("Perishable Goods", first.title)
        assertEquals("Dornford Yates", first.author)
        assertEquals(
            "https://standardebooks.org/images/covers/dornford-yates_perishable-goods-48e72061-cover@2x.jpg",
            first.coverUrl,
        )
        assertEquals(listOf("Adventure", "Fiction"), first.tags)

        val second = page.results[1]
        assertEquals("joshua-slocum", second.authorSlug)
        assertEquals("sailing-alone-around-the-world", second.bookSlug)
        assertEquals("Sailing Alone Around the World", second.title)
        assertEquals("Joshua Slocum", second.author)
        assertEquals(listOf("Adventure", "Memoir", "Nonfiction", "Travel"), second.tags)
    }

    @Test
    fun `parseListPage detects hasNext from rel=next anchor`() {
        val page = SeHtmlParser.parseListPage(twoEntryListing)
        assertTrue(page.hasNext)
    }

    @Test
    fun `parseListPage flags last page as hasNext=false`() {
        // Same listing with the rel=next anchor scrubbed — last page
        // shape per SE markup.
        val tail = twoEntryListing.replace(
            """<a href="/ebooks?page=2&amp;view=list" rel="next">Next</a>""",
            """<a aria-disabled="true">Next</a>""",
        )
        val page = SeHtmlParser.parseListPage(tail)
        assertFalse(page.hasNext)
    }

    @Test
    fun `parseListPage returns empty list on unrelated HTML`() {
        val page = SeHtmlParser.parseListPage("<html><body>nope</body></html>")
        assertTrue(page.results.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test
    fun `parseBookDescription extracts paragraphs from the description section`() {
        val bookPage = """
            <main>
                <section id="description">
                    <h2>Description</h2>
                    <p>Jonathan Mansel, leader of the Blind Corner expedition, has been robbed.</p>
                    <p>In <i>Perishable Goods</i>, set six months after the events of Blind Corner,
                    we see the newly rich Richard Chandos and friends once again pitted against
                    the inimitable Rose Noble.</p>
                </section>
            </main>
        """.trimIndent()

        val desc = SeHtmlParser.parseBookDescription(bookPage)
        assertNotNull(desc)
        assertTrue(desc!!.contains("Jonathan Mansel"))
        assertTrue(desc.contains("Rose Noble"))
        // Inline tags (<i>) are stripped but the surrounding text survives.
        assertTrue(desc.contains("Perishable Goods"))
        assertFalse(desc.contains("<i>"))
        assertFalse(desc.contains("</p>"))
    }

    @Test
    fun `parseBookDescription returns null on a page without description section`() {
        val pageWithoutDescription = "<html><body><h1>Nope</h1></body></html>"
        assertNull(SeHtmlParser.parseBookDescription(pageWithoutDescription))
    }

    @Test
    fun `parseBookDescription decodes curly quotes, dashes, and accents (#1628)`() {
        // The old local htmlDecode handled only ~7 named entities; curly
        // quotes / em-dashes / accents leaked into the browse card + TTS.
        // Field text now flows through the shared htmlToInlineText (jsoup).
        val bookPage = """
            <main><section id="description">
                <h2>Description</h2>
                <p>A rogue&#8217;s tale &mdash; wit, <em>daring</em>&hellip; and a curly &ldquo;quote&rdquo;.</p>
                <p>Caf&eacute; na&#xEF;ve &amp; bold.</p>
            </section></main>
        """.trimIndent()

        val desc = SeHtmlParser.parseBookDescription(bookPage)
        assertNotNull(desc)
        assertEquals(
            "A rogue’s tale — wit, daring… and a curly “quote”.\n\nCafé naïve & bold.",
            desc,
        )
        // No raw entity codes and no inline markup survive.
        assertFalse(desc!!.contains("&#"))
        assertFalse(desc.contains("&mdash;"))
        assertFalse(desc.contains("&ldquo;"))
        assertFalse(desc.contains("<em>"))
    }
}
