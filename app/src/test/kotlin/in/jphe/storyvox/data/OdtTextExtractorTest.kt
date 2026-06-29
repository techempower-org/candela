package `in`.jphe.storyvox.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/** Issue #1310 — ODF→text conversion + the ZIP read for `.odt` import. */
class OdtTextExtractorTest {

    @Test
    fun `paragraphs and headings become blank-line-separated blocks; spans inline`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>""" +
            "<office:document-content>" +
            """<office:automatic-styles><style:style style:name="P1"/></office:automatic-styles>""" +
            "<office:body><office:text>" +
            """<text:h text:outline-level="1">Chapter One</text:h>""" +
            "<text:p>Hello <text:span>brave</text:span> world.</text:p>" +
            "</office:text></office:body></office:document-content>"
        assertEquals("Chapter One\n\nHello brave world.", OdtTextExtractor.odfXmlToText(xml))
    }

    @Test
    fun `line break, tab and space-run map to their characters`() {
        val xml = "<office:text>" +
            """<text:p>a<text:line-break/>b<text:tab/>c<text:s text:c="3"/>d</text:p>""" +
            """<text:p>one<text:s/>space</text:p>""" +
            "</office:text>"
        assertEquals("a\nb\tc   d\n\none space", OdtTextExtractor.odfXmlToText(xml))
    }

    @Test
    fun `xml entities are decoded, ampersand last`() {
        val xml = "<office:text><text:p>Tom &amp; Jerry &lt;tag&gt; &quot;q&quot; &amp;lt;</text:p></office:text>"
        assertEquals("""Tom & Jerry <tag> "q" &lt;""", OdtTextExtractor.odfXmlToText(xml))
    }

    @Test
    fun `unknown tags are stripped but their text content is kept`() {
        val xml = "<office:text>" +
            """<text:p>See <text:a xlink:href="http://x">the link</text:a> here.</text:p>""" +
            """<text:list><text:list-item><text:p>item</text:p></text:list-item></text:list>""" +
            "</office:text>"
        assertEquals("See the link here.\n\nitem", OdtTextExtractor.odfXmlToText(xml))
    }

    @Test
    fun `without an office text body the whole input is processed`() {
        // Defensive fallback — still recover paragraph text.
        assertEquals("Loose text.", OdtTextExtractor.odfXmlToText("<text:p>Loose text.</text:p>"))
    }

    @Test
    fun `extract reads content xml out of the odt zip`() {
        val odt = zipOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to "<office:text><text:p>From the archive.</text:p></office:text>",
        )
        assertEquals("From the archive.", OdtTextExtractor.extract(odt))
    }

    @Test
    fun `extract returns empty when there is no content xml`() {
        val notOdt = zipOf("mimetype" to "application/zip", "other.xml" to "<x/>")
        assertEquals("", OdtTextExtractor.extract(notOdt))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
