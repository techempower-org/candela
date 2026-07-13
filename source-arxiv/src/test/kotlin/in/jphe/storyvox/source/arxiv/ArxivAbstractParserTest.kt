package `in`.jphe.storyvox.source.arxiv

import `in`.jphe.storyvox.source.arxiv.net.ArxivAbstractParser
import `in`.jphe.storyvox.source.arxiv.net.formatAuthors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #378 — pins the abstract-page parser against a real-shaped
 * `arxiv.org/abs/<id>` fixture. The fixture lives at
 * `src/test/resources/abs-fixture.html` so the parser is exercised
 * against meta-tag + fallback-block paths the way arXiv actually
 * emits them. A silent change in the citation_* meta-tag contract
 * surfaces here at CI.
 */
class ArxivAbstractParserTest {

    private val fixture: String = readFixture("abs-fixture.html")

    @Test
    fun `parses title, authors, abstract, subjects, and comments`() {
        val parsed = ArxivAbstractParser.parse(fixture)
        assertEquals("Attention Is All You Need", parsed.title)
        // 8 authors in document order — citation_author meta tags drive
        // the order; the `<div class="authors">` HTML block is a fallback.
        assertEquals(8, parsed.authors.size)
        assertEquals("Vaswani, Ashish", parsed.authors.first())
        assertEquals("Polosukhin, Illia", parsed.authors.last())
        // Abstract begins with the canonical line; whitespace collapses.
        assertTrue(parsed.abstract.startsWith("The dominant sequence transduction models"))
        // Subjects row preserves both primary and secondary entries.
        assertTrue(parsed.subjects.contains("Computation and Language"))
        assertTrue(parsed.subjects.contains("Machine Learning"))
        // Comments row is optional; this fixture supplies it.
        assertNotNull(parsed.comments)
        assertTrue(parsed.comments!!.contains("NIPS 2017"))
    }

    @Test
    fun `chapter html includes title byline subjects comments and abstract`() {
        val parsed = ArxivAbstractParser.parse(fixture)
        val html = ArxivAbstractParser.toChapterHtml(parsed)
        assertTrue("title rendered", html.contains("<h2>Attention Is All You Need</h2>"))
        assertTrue("byline rendered", html.contains("<em>by Vaswani, Ashish,"))
        assertTrue("subjects rendered", html.contains("Subjects:"))
        assertTrue("comments rendered", html.contains("NIPS 2017"))
        assertTrue("abstract rendered", html.contains("Abstract."))
        assertTrue("abstract body present", html.contains("Transformer"))
    }

    @Test
    fun `chapter plain text reads as a narratable paragraph`() {
        val parsed = ArxivAbstractParser.parse(fixture)
        val plain = ArxivAbstractParser.toChapterPlain(parsed)
        // Title on its own line so TTS gives it a pause.
        assertTrue(plain.startsWith("Attention Is All You Need"))
        // Byline reads as a natural sentence (not a comma-separated list).
        assertTrue(plain.contains("by Vaswani, Ashish,"))
        assertTrue(plain.contains("Subjects: "))
        assertTrue(plain.contains("Abstract. The dominant sequence"))
        // No raw HTML leaks through.
        assertTrue("no `<` in plain output", !plain.contains("<"))
        assertTrue("no `>` in plain output", !plain.contains(">"))
    }

    @Test
    fun `formatAuthors handles zero, one, two, and many cases`() {
        assertEquals("", formatAuthors(emptyList()))
        assertEquals("Alice", formatAuthors(listOf("Alice")))
        assertEquals("Alice and Bob", formatAuthors(listOf("Alice", "Bob")))
        assertEquals(
            "Alice, Bob, and Carol",
            formatAuthors(listOf("Alice", "Bob", "Carol")),
        )
    }

    @Test
    fun `parse handles missing meta tags via block fallback`() {
        // No citation_* meta tags at all — parser should fall through to
        // the H1 / blockquote fallback path.
        val noMeta = """
            <html><body>
              <h1 class="title mathjax"><span class="descriptor">Title:</span> Fallback Title</h1>
              <blockquote class="abstract">
                <span class="descriptor">Abstract:</span>
                Fallback abstract body.
              </blockquote>
            </body></html>
        """.trimIndent()
        val parsed = ArxivAbstractParser.parse(noMeta)
        assertEquals("Fallback Title", parsed.title)
        assertTrue(parsed.abstract.startsWith("Fallback abstract body"))
        assertTrue(parsed.authors.isEmpty())
        // No comments row → nullable comments field stays null.
        assertNull(parsed.comments)
    }

    @Test
    fun `decodes hex, named, and accented entities the local table missed (#1628)`() {
        // The old decodeXmlEntities handled only 6 XML entities — hex refs
        // (&#xF6;), named accents (&ouml;), and curly quotes (&#8217;) leaked
        // into the chapter body and TTS narration. The shared htmlToInlineText
        // decodes them all. citation_* meta tags carry the field text.
        val html = """
            <html><head>
            <meta name="citation_title" content="Caf&#233; Habits &amp; Sch&ouml;lkopf&#8217;s Method">
            <meta name="citation_author" content="Sch&#xF6;lkopf, Bernhard">
            <meta name="citation_abstract" content="A na&#xEF;ve rodent&#x2019;s tale &mdash; fun&#8230;">
            </head><body></body></html>
        """.trimIndent()
        val parsed = ArxivAbstractParser.parse(html)

        assertEquals("Café Habits & Schölkopf’s Method", parsed.title)
        assertEquals(listOf("Schölkopf, Bernhard"), parsed.authors)
        assertEquals("A naïve rodent’s tale — fun…", parsed.abstract)
        // No raw entity codes reach the reader / narration.
        listOf(parsed.title, parsed.authors.first(), parsed.abstract).forEach {
            assertFalse("no named entity leaks", it.contains("&amp;") || it.contains("&ouml;"))
            assertFalse("no numeric ref leaks", it.contains("&#"))
        }
    }

    private fun readFixture(name: String): String {
        val stream = javaClass.classLoader!!.getResourceAsStream(name)
            ?: error("Fixture not on test classpath: $name")
        return stream.bufferedReader().use { it.readText() }
    }
}
