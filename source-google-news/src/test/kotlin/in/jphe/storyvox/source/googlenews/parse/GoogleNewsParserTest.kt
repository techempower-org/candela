package `in`.jphe.storyvox.source.googlenews.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1238 — parser unit tests. Plain JUnit (no Robolectric): the
 * `javax.xml` DOM builder resolves on the JVM test runtime, so the
 * fixture in `src/test/resources/google-news-top.xml` parses directly.
 */
class GoogleNewsParserTest {

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResource("google-news-top.xml")) {
            "google-news-top.xml fixture missing from test resources"
        }.readText()

    @Test
    fun `parses channel title and all items`() {
        val feed = GoogleNewsParser.parse(fixture())
        assertEquals("Top stories - Google News", feed.title)
        assertEquals(2, feed.items.size)
    }

    @Test
    fun `prefers the source element for publisher and strips the title suffix`() {
        val item = GoogleNewsParser.parse(fixture()).items[0]
        assertEquals("Escalating tensions threaten interim peace deal", item.title)
        assertEquals("The Guardian", item.publisher)
        assertEquals("CBMiqAFExample1", item.guid)
        assertNotNull("pubDate should parse to epoch ms", item.publishedAtEpochMs)
    }

    @Test
    fun `falls back to the title suffix when there is no source element`() {
        val item = GoogleNewsParser.parse(fixture()).items[1]
        assertEquals("Market rallies on rate-cut hopes", item.title)
        assertEquals("Bloomberg", item.publisher)
    }

    @Test
    fun `related headlines drop the main story and keep the siblings`() {
        val item = GoogleNewsParser.parse(fixture()).items[0]
        assertEquals(listOf("Diplomats meet as deadline nears"), item.relatedHeadlines)
    }

    @Test
    fun `malformed input yields an empty feed rather than throwing`() {
        val feed = GoogleNewsParser.parse("not xml <<<")
        assertEquals("", feed.title)
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `splitTitle trusts the source text and strips a matching suffix`() {
        assertEquals(
            "Headline" to "The Verge",
            GoogleNewsParser.splitTitle("Headline - The Verge", "The Verge"),
        )
    }

    @Test
    fun `splitTitle without a source element splits on the last dash`() {
        assertEquals(
            "A - B story" to "Publisher",
            GoogleNewsParser.splitTitle("A - B story - Publisher", ""),
        )
    }

    @Test
    fun `splitTitle leaves the publisher empty when there is no separator`() {
        assertEquals(
            "Just a headline" to "",
            GoogleNewsParser.splitTitle("Just a headline", ""),
        )
    }

    @Test
    fun `related headlines strip tags and decode entities via shared util (#1628)`() {
        // Field cleanup moved from the local stripHtml (7-entity table) to
        // the shared htmlToInlineText. Tag-strip + &amp;/&lt;/&gt; are
        // behaviour-preserving; the curly quote (&#8217;) is the entity-gap
        // FIX the old table left raw in the browse card / narration.
        val descHtml =
            """<ol><li><a href="#">Mat &amp; Friends&#8217; <b>big</b> day</a></li>""" +
                """<li><a href="#">Other &lt;coverage&gt;</a></li>""" +
                // R1: old stripHtml replaced a tag with a SPACE; the shared
                // util uses source whitespace, so an intra-word tag merges.
                """<li><a href="#">Sky<i>net</i>rises</a></li></ol>"""
        assertEquals(
            listOf("Mat & Friends’ big day", "Other <coverage>", "Skynetrises"),
            GoogleNewsParser.relatedHeadlinesFrom(descHtml, exclude = ""),
        )
    }
}
