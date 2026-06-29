package `in`.jphe.storyvox.source.bookshare.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #1002 — DAISY 3 DTBook parsing. Robolectric-backed because
 * [DaisyParser] uses `android.util.Xml` (same posture as `:source-palace`'s
 * OPDS parser tests). These exercise the non-gated half of the Bookshare
 * source: turning a DTBook document into ordered, re-narratable chapters.
 */
@RunWith(RobolectricTestRunner::class)
class DaisyParserTest {

    private val twoChapterDtbook = """
        <?xml version="1.0" encoding="UTF-8"?>
        <dtbook version="2005-3" xml:lang="en">
          <head>
            <meta name="dc:Title" content="A Tale of Testing"/>
            <meta name="dc:Creator" content="Ada Lovelace"/>
          </head>
          <book>
            <frontmatter>
              <doctitle>A Tale of Testing</doctitle>
              <docauthor>Ada Lovelace</docauthor>
            </frontmatter>
            <bodymatter>
              <level1 id="ch1">
                <h1>Chapter One</h1>
                <p>It was the best of tests.</p>
                <p>It was the worst of tests.</p>
              </level1>
              <level1 id="ch2">
                <h1>Chapter Two</h1>
                <p>The second chapter begins.</p>
              </level1>
            </bodymatter>
          </book>
        </dtbook>
    """.trimIndent()

    @Test
    fun `extracts book title and author from dc meta`() {
        val book = DaisyParser.parseDtbook(twoChapterDtbook)
        assertEquals("A Tale of Testing", book.title)
        assertEquals("Ada Lovelace", book.author)
    }

    @Test
    fun `splits level1 sections into ordered chapters with titles`() {
        val book = DaisyParser.parseDtbook(twoChapterDtbook)
        assertEquals(2, book.chapters.size)
        assertEquals("ch1", book.chapters[0].id)
        assertEquals("Chapter One", book.chapters[0].title)
        assertEquals("ch2", book.chapters[1].id)
        assertEquals("Chapter Two", book.chapters[1].title)
    }

    @Test
    fun `captures paragraph text into plain and html bodies`() {
        val first = DaisyParser.parseDtbook(twoChapterDtbook).chapters[0]
        // Plain body keeps both paragraphs, joined by a blank line.
        assertTrue(first.plainBody.contains("It was the best of tests."))
        assertTrue(first.plainBody.contains("It was the worst of tests."))
        // HTML body wraps each paragraph in <p>.
        assertTrue(first.htmlBody.contains("<p>It was the best of tests.</p>"))
        assertTrue(first.htmlBody.contains("<p>It was the worst of tests.</p>"))
    }

    @Test
    fun `falls back to a numbered title when a section has no heading`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dtbook version="2005-3">
              <book><bodymatter>
                <level1><p>No heading here.</p></level1>
              </bodymatter></book>
            </dtbook>
        """.trimIndent()
        val book = DaisyParser.parseDtbook(xml)
        assertEquals(1, book.chapters.size)
        assertEquals("Section 1", book.chapters[0].title)
        assertTrue(book.chapters[0].plainBody.contains("No heading here."))
    }

    @Test
    fun `collapses whitespace across wrapped paragraph text`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dtbook version="2005-3">
              <book><bodymatter>
                <level1 id="x">
                  <h1>Title</h1>
                  <p>
                    line one
                    line two
                  </p>
                </level1>
              </bodymatter></book>
            </dtbook>
        """.trimIndent()
        val ch = DaisyParser.parseDtbook(xml).chapters.single()
        assertEquals("line one line two", ch.plainBody)
    }

    @Test
    fun `escapes html-significant characters in the html body`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <dtbook version="2005-3">
              <book><bodymatter>
                <level1 id="x">
                  <h1>Title</h1>
                  <p>5 &lt; 6 &amp; 7 &gt; 6</p>
                </level1>
              </bodymatter></book>
            </dtbook>
        """.trimIndent()
        val ch = DaisyParser.parseDtbook(xml).chapters.single()
        // Parser decodes the entities to text, then re-escapes for HTML output.
        assertEquals("5 < 6 & 7 > 6", ch.plainBody)
        assertTrue(ch.htmlBody.contains("5 &lt; 6 &amp; 7 &gt; 6"))
    }
}
