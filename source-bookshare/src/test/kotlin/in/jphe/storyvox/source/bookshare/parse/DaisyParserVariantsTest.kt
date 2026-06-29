package `in`.jphe.storyvox.source.bookshare.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #1315 — structural / encoding edge cases for the DAISY parsers,
 * complementing [DaisyParserTest] (DAISY 3 happy path) and [Daisy202ParserTest]
 * (a real 2.02 package). Robolectric-backed because both parsers use
 * `android.util.Xml`, matching the existing tests' posture.
 *
 * Covers: heading-less / id-less sections (title + id fallbacks), nested
 * `<level>` flattening, a second heading falling through to body text, page /
 * note markers dropped, inline markup keeping a paragraph intact, XML entity
 * decode + HTML re-escape, whitespace collapse, empty sections, malformed XML;
 * and for 2.02: a missing `ncc.html`, headings with no resolvable content
 * (empty-body chapters), and an anchor-less heading being skipped.
 */
@RunWith(RobolectricTestRunner::class)
class DaisyParserVariantsTest {

    private fun dtbook(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><dtbook version="2005-3">$body</dtbook>"""

    // ── DAISY 3 DTBook (DaisyParser.parseDtbook) ──────────────────────────

    @Test fun `a section without a heading falls back to a Section N title`() {
        val book = DaisyParser.parseDtbook(
            dtbook("""<level1 id="s1"><p>Body with no heading.</p></level1>"""),
        )
        assertEquals(1, book.chapters.size)
        assertEquals("s1", book.chapters[0].id)
        assertEquals("Section 1", book.chapters[0].title)
        assertEquals("Body with no heading.", book.chapters[0].plainBody)
    }

    @Test fun `a section without an id falls back to a level-N id`() {
        val book = DaisyParser.parseDtbook(
            dtbook("""<level1><h1>Titled</h1><p>x</p></level1>"""),
        )
        assertEquals("level-1", book.chapters[0].id)
        assertEquals("Titled", book.chapters[0].title)
    }

    @Test fun `nested levels flatten into a single chapter`() {
        val book = DaisyParser.parseDtbook(
            dtbook(
                """
                <level1 id="outer"><h1>Outer</h1><p>Outer para.</p>
                  <level><h2>Inner</h2><p>Inner para.</p></level>
                </level1>
                """.trimIndent(),
            ),
        )
        assertEquals(1, book.chapters.size)
        assertEquals("Outer", book.chapters[0].title)
        assertTrue(book.chapters[0].plainBody.contains("Outer para."))
        assertTrue(book.chapters[0].plainBody.contains("Inner para."))
    }

    @Test fun `only the first heading is the title and a later heading becomes body`() {
        val book = DaisyParser.parseDtbook(
            dtbook("""<level1 id="c"><h1>Real Title</h1><h2>Subhead</h2><p>Body.</p></level1>"""),
        )
        assertEquals("Real Title", book.chapters[0].title)
        assertTrue(book.chapters[0].plainBody.contains("Subhead"))
        assertTrue(book.chapters[0].plainBody.contains("Body."))
    }

    @Test fun `page and note markers are dropped from the narrated body`() {
        val book = DaisyParser.parseDtbook(
            dtbook("""<level1 id="c"><h1>H</h1><p>Before<pagenum>42</pagenum>after.</p></level1>"""),
        )
        val body = book.chapters[0].plainBody
        assertTrue(body.contains("Before"))
        assertTrue(body.contains("after."))
        assertFalse("page number leaked into narration", body.contains("42"))
    }

    @Test fun `inline markup keeps a paragraph intact`() {
        val book = DaisyParser.parseDtbook(
            dtbook("""<level1 id="c"><h1>H</h1><p>Hello <em>brave</em> world.</p></level1>"""),
        )
        assertEquals("Hello brave world.", book.chapters[0].plainBody)
    }

    @Test fun `entities are decoded in plain text and re-escaped in html`() {
        val book = DaisyParser.parseDtbook(
            dtbook("""<level1 id="c"><h1>H</h1><p>Tom &amp; Jerry &lt;tag&gt;</p></level1>"""),
        )
        assertEquals("Tom & Jerry <tag>", book.chapters[0].plainBody)
        assertEquals("<p>Tom &amp; Jerry &lt;tag&gt;</p>", book.chapters[0].htmlBody)
    }

    @Test fun `whitespace and newlines collapse to single spaces`() {
        val book = DaisyParser.parseDtbook(
            dtbook("<level1 id=\"c\"><h1>H</h1><p>Lots    of\n\n   space</p></level1>"),
        )
        assertEquals("Lots of space", book.chapters[0].plainBody)
    }

    @Test fun `an empty section still yields a chapter with an empty body`() {
        val book = DaisyParser.parseDtbook(dtbook("""<level1 id="empty"></level1>"""))
        assertEquals(1, book.chapters.size)
        assertEquals("empty", book.chapters[0].id)
        assertEquals("Section 1", book.chapters[0].title)
        assertEquals("", book.chapters[0].plainBody)
    }

    @Test(expected = DaisyParseException::class)
    fun `malformed DTBook xml throws DaisyParseException`() {
        // <p> is never closed before </level1> → strict pull parser rejects it.
        DaisyParser.parseDtbook(dtbook("""<level1 id="c"><h1>H</h1><p>oops</level1>"""))
    }

    // ── DAISY 2.02 (Daisy202Parser.parsePackage) ──────────────────────────

    private fun pkg(vararg files: Pair<String, String>): Map<String, ByteArray> =
        files.associate { (k, v) -> k to v.toByteArray(Charsets.UTF_8) }

    @Test(expected = DaisyParseException::class)
    fun `a package with no ncc throws DaisyParseException`() {
        Daisy202Parser.parsePackage(emptyMap())
    }

    @Test fun `ncc headings with no resolvable content yield empty-body chapters`() {
        // ncc.html only — the referenced .smil / content docs are absent, so each
        // heading resolves to a titled chapter with an empty body (not dropped).
        val ncc = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html><head>
              <meta name="dc:title" content="My Book"/>
              <meta name="dc:creator" content="Auth"/>
            </head><body>
              <h1 id="h1"><a href="ch.smil#t1">First</a></h1>
              <h1 id="h2"><a href="ch.smil#t2">Second</a></h1>
            </body></html>
        """.trimIndent()
        val book = Daisy202Parser.parsePackage(pkg("ncc.html" to ncc))
        assertEquals("My Book", book.title)
        assertEquals("Auth", book.author)
        assertEquals(2, book.chapters.size)
        assertEquals("First", book.chapters[0].title)
        assertEquals("h1", book.chapters[0].id)
        assertEquals("", book.chapters[0].plainBody)
        assertEquals("Second", book.chapters[1].title)
    }

    @Test fun `an ncc heading without an anchor href is skipped`() {
        val ncc = """
            <?xml version="1.0" encoding="UTF-8"?>
            <html><head><meta name="dc:title" content="B"/></head><body>
              <h1 id="h1">No link here</h1>
              <h1 id="h2"><a href="c.smil#t">Linked</a></h1>
            </body></html>
        """.trimIndent()
        val book = Daisy202Parser.parsePackage(pkg("ncc.html" to ncc))
        assertEquals(1, book.chapters.size)
        assertEquals("Linked", book.chapters[0].title)
    }
}
