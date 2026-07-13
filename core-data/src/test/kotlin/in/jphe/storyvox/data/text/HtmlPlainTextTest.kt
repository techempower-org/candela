package `in`.jphe.storyvox.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1628 — shared HTML → plaintext conversion, consumed by every source
 * for `ChapterContent.plainBody`.
 *
 * Content that flattens to one line (the #1619 / #1623 / #1626 / #1627 class)
 * produces a run-on blob, breaks paragraph-level navigation (which keys on
 * blank lines), and — without entity decoding — leaks `&…;` codes into the
 * reader and TTS narration. [htmlToPlainText] parses to a DOM, drops
 * non-content nodes, inserts `\n\n` after each block element and `\n` for
 * `<br>`, and decodes entities natively.
 *
 * Pure-function tests over both full XHTML documents (EPUB chapter files) and
 * bare fragments (RSS item bodies / scraped chapter HTML).
 */
class HtmlPlainTextTest {

    /** Blank-line-separated paragraph count — the signal paragraph-nav uses. */
    private fun paragraphCount(body: String): Int =
        body.split(Regex("\n[ \t]*\n")).count { it.isNotBlank() }

    // ── full XHTML documents (EPUB chapter files) ───────────────────

    @Test
    fun `full XHTML document drops head, style, and title text`() {
        val html = """
            <html><head><title>Book Title</title>
            <style>.c{color:red}</style></head>
            <body><p>Hello world.</p></body></html>
        """.trimIndent()
        val out = html.htmlToPlainText()

        assertEquals("Hello world.", out)
        assertFalse("title text leaked", out.contains("Book Title"))
        assertFalse("css leaked", out.contains("color"))
    }

    @Test
    fun `script content is dropped`() {
        val out = "<body><script>alert('boom')</script><p>Real content.</p></body>"
            .htmlToPlainText()
        assertFalse(out.contains("alert"))
        assertFalse(out.contains("boom"))
        assertTrue(out.contains("Real content."))
    }

    @Test
    fun `realistic EPUB chapter keeps paragraphs, decodes entities, drops style`() {
        val html = """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml"><head>
            <title>Ch 1</title>
            <link rel="stylesheet" type="text/css" href="../css/style.css"/>
            <style>p{text-indent:1em}</style>
            </head>
            <body>
            <h1>Chapter 1</h1>
            <p>It was a bright cold day in April, and the clocks were striking thirteen.</p>
            <p>Winston Smith, his chin nuzzled into his breast&#8217;s warmth, slipped
            quickly through the glass doors.</p>
            </body></html>
        """.trimIndent()
        val out = html.htmlToPlainText()

        assertEquals("three paragraphs preserved", 3, paragraphCount(out))
        assertTrue(out.contains("Chapter 1"))
        assertTrue("entity decoded to curly apostrophe", out.contains("breast’s"))
        // Intra-paragraph source newline is HTML whitespace -> single space.
        assertTrue(out.contains("slipped quickly"))
        assertFalse("css never reaches prose", out.contains("text-indent"))
        assertFalse("head title never reaches prose", out.contains("Ch 1"))
        assertFalse(out.contains("&#8217;"))
    }

    // ── bare fragments (RSS item bodies, scraped chapter HTML) ──────

    @Test
    fun `bare fragment without html or body wrapper keeps paragraphs`() {
        // RSS content:encoded / Standard Ebooks chapter fragment shape.
        val out = "<p>First para.</p><p>Second para.</p>".htmlToPlainText()
        assertEquals("First para.\n\nSecond para.", out)
        assertEquals(2, paragraphCount(out))
    }

    @Test
    fun `fragment with mixed block levels and entities`() {
        val out = ("<h2>Heading</h2><p>Body with &amp; an &rsquo;entity&rsquo; " +
            "and a<br>soft break.</p>").htmlToPlainText()
        assertEquals(2, paragraphCount(out))
        assertTrue(out.contains("Body with & an ’entity’"))
        assertTrue("br is a soft line break", out.contains("a\nsoft break."))
        assertFalse(out.contains("&amp;"))
        assertFalse(out.contains("&rsquo;"))
    }

    // ── general block / inline / entity / whitespace contract ───────

    @Test
    fun `consecutive paragraphs are separated by a blank line`() {
        val out = "<body><p>First paragraph.</p><p>Second paragraph.</p></body>"
            .htmlToPlainText()
        assertEquals("First paragraph.\n\nSecond paragraph.", out)
        assertEquals(2, paragraphCount(out))
    }

    @Test
    fun `br becomes a single soft line break, not a paragraph break`() {
        val out = "<body><p>Line one<br>Line two</p></body>".htmlToPlainText()
        assertEquals("Line one\nLine two", out)
    }

    @Test
    fun `headings are separated from the following paragraph`() {
        val out = "<body><h1>Chapter One</h1><p>The story begins.</p></body>"
            .htmlToPlainText()
        assertEquals("Chapter One\n\nThe story begins.", out)
    }

    @Test
    fun `nested blocks do not stack up extra blank lines`() {
        val out = "<body><div><p>Inside.</p></div><p>After.</p></body>".htmlToPlainText()
        // <p> close + <div> close would emit two blank lines; capped to one.
        assertEquals("Inside.\n\nAfter.", out)
    }

    @Test
    fun `inline tags stay on the same line`() {
        val out = "<body><p>a <em>b</em> c <strong>d</strong></p></body>".htmlToPlainText()
        assertFalse("inline tags must not introduce line breaks", out.contains("\n"))
        assertTrue(out.contains("b"))
        assertTrue(out.contains("d"))
    }

    @Test
    fun `named and numeric entities are decoded, not leaked`() {
        val out = "<body><p>Tom &amp; Jerry &rsquo;quote&rsquo; &mdash; end&hellip;</p></body>"
            .htmlToPlainText()
        assertTrue(out.contains("Tom & Jerry"))
        assertTrue("right single quote decoded", out.contains("’"))
        assertTrue("em-dash decoded", out.contains("—"))
        assertTrue("ellipsis decoded", out.contains("…"))
        assertFalse(out.contains("&amp;"))
        assertFalse(out.contains("&rsquo;"))
        assertFalse(out.contains("&mdash;"))
        assertFalse(out.contains("&hellip;"))
    }

    @Test
    fun `blank and empty inputs yield empty string`() {
        assertEquals("", "".htmlToPlainText())
        assertEquals("", "   \n\t ".htmlToPlainText())
        assertEquals("", "<body></body>".htmlToPlainText())
    }

    @Test
    fun `single paragraph produces no spurious line breaks`() {
        val out = "<body><p>Just one paragraph here.</p></body>".htmlToPlainText()
        assertEquals("Just one paragraph here.", out)
        assertFalse(out.contains("\n"))
    }

    // ── htmlToInlineText: single-line metadata contract (#1628) ─────

    @Test
    fun `htmlToInlineText collapses blocks and newlines to one line`() {
        val out = "<h2>Title</h2><p>First line\n  second line</p>".htmlToInlineText()
        assertEquals("Title First line second line", out)
        assertFalse("inline output must be one line", out.contains("\n"))
    }

    @Test
    fun `htmlToInlineText strips tags and drops non-content`() {
        val out = "<style>.c{}</style><p>Body <em>text</em>.</p><script>x()</script>"
            .htmlToInlineText()
        assertEquals("Body text.", out)
    }

    @Test
    fun `htmlToInlineText blank and empty inputs yield empty string`() {
        assertEquals("", "".htmlToInlineText())
        assertEquals("", "   \n\t ".htmlToInlineText())
        assertEquals("", "<body></body>".htmlToInlineText())
    }

    @Test
    fun `htmlToInlineText passes plain (non-markup) metadata through unchanged`() {
        // arXiv citation_* meta content / SE schema:name are plain text.
        assertEquals("Attention Is All You Need", "Attention Is All You Need".htmlToInlineText())
    }

    // ── #1628 named failure modes: RTL, nested lists, CDATA, entities ─

    @Test
    fun `RTL text survives intact in both converters`() {
        // Codepoint-safe extraction — no byte-level mangling of Arabic/Hebrew.
        val arabic = "<p>مرحبا بالعالم</p>"
        assertEquals("مرحبا بالعالم", arabic.htmlToPlainText())
        assertEquals("مرحبا بالعالم", arabic.htmlToInlineText())
        val mixed = "<p>Hello <span>שלום</span> world</p>"
        assertEquals("Hello שלום world", mixed.htmlToInlineText())
    }

    @Test
    fun `nested lists keep every item and leak no list markup`() {
        val html = "<ul><li>One<ul><li>Two</li><li>Three</li></ul></li><li>Four</li></ul>"
        // Inline: one clean spaced run — the metadata contract.
        assertEquals("One Two Three Four", html.htmlToInlineText())

        val plain = html.htmlToPlainText()
        listOf("One", "Two", "Three", "Three", "Four").forEach {
            assertTrue("item $it present", plain.contains(it))
        }
        assertFalse("no <li> markup leaks", plain.contains("<li"))
        // Known htmlToPlainText limitation (pre-#1628, shared by all migrated
        // sources): a parent <li>'s own text abuts its first nested child's
        // text ("OneTwo") because block-nav emits the break on close, not
        // open. Harmless for prose; nested lists are rare in narrated bodies.
        // Pinned so a future change to the block walk is a deliberate choice.
        assertEquals("OneTwo\n\nThree\n\nFour", plain)
    }

    @Test
    fun `CDATA markers never leak and unwrapped feed content cleans`() {
        // RSS `content:encoded` arrives CDATA-unwrapped from the XML layer;
        // the util then cleans the inner HTML. Markers must never survive.
        val mixed = "<p>Before</p><![CDATA[note text]]><p>After</p>"
        listOf(mixed.htmlToPlainText(), mixed.htmlToInlineText()).forEach { out ->
            assertFalse("no CDATA open marker", out.contains("CDATA"))
            assertFalse("no CDATA close marker", out.contains("]]>"))
            assertTrue(out.contains("Before"))
            assertTrue(out.contains("After"))
        }
        // The realistic path: content already unwrapped by the feed parser.
        val unwrapped = "<p>Chapter body with an &amp; and a curly quote&#8217;s tail.</p>"
        assertEquals("Chapter body with an & and a curly quote’s tail.", unwrapped.htmlToPlainText())
    }

    @Test
    fun `exotic entities decode — the entity-table gap fix`() {
        // The per-source regex tables (arXiv/PLOS/Wikisource/SE/Prime Gaming)
        // decoded only ~6-10 named entities, leaking curly quotes, hex refs,
        // and accents into the reader + TTS narration. jsoup decodes them all.
        val html = "<p>&rsquo;&lsquo;&ldquo;&rdquo; &mdash; &hellip; " +
            "&#x2019;hex &#8217;dec Sch&ouml;n caf&eacute; na&#xEF;ve</p>"
        listOf(html.htmlToPlainText(), html.htmlToInlineText()).forEach { out ->
            assertTrue("right single quote", out.contains("’"))
            assertTrue("left single quote", out.contains("‘"))
            assertTrue("left double quote", out.contains("“"))
            assertTrue("right double quote", out.contains("”"))
            assertTrue("em-dash", out.contains("—"))
            assertTrue("ellipsis", out.contains("…"))
            assertTrue("hex numeric ref", out.contains("’hex"))
            assertTrue("decimal numeric ref", out.contains("’dec"))
            assertTrue("named accent (o-umlaut)", out.contains("Schön"))
            assertTrue("named accent (e-acute)", out.contains("café"))
            assertTrue("hex accent (i-diaeresis)", out.contains("naïve"))
            assertFalse("no raw entity survives", out.contains("&"))
        }
    }

    @Test
    fun `astral-plane numeric refs decode via surrogate pair, never throw (#1539)`() {
        // The #1539 regression: Char(code) threw above U+FFFF. jsoup uses the
        // full-codepoint decode, so an emoji numeric ref round-trips.
        assertEquals("Fun 😀!", "Fun &#128512;!".htmlToInlineText())
    }

    @Test
    fun `malformed numeric refs do not leak raw and do not throw`() {
        // Out-of-range / lone-surrogate refs follow HTML5: jsoup emits U+FFFD
        // rather than the per-source decoders' old literal passthrough. Never
        // the raw `&#…;` code (which TTS would try to narrate).
        val out = "bad &#1114112; ref and &#55296; too".htmlToInlineText()
        assertFalse("no raw numeric ref leaks", out.contains("&#"))
        assertTrue(out.contains("bad"))
        assertTrue(out.contains("ref"))
    }
}
