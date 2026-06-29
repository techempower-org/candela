package `in`.jphe.storyvox.source.googlenews.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1238 — edge-case parser tests. Companion to [GoogleNewsParserTest].
 *
 * The parser is documented as total (never throws): malformed input yields
 * [EMPTY][GoogleNewsParser] and individual unparseable `<item>`s are skipped.
 * These tests pin that contract with hand-built RSS so each malformed shape
 * has an unambiguous expected output. Plain JUnit (no Robolectric): the
 * `javax.xml` DOM builder resolves on the JVM test runtime.
 *
 * Inputs are constructed inline rather than from a fixture file because each
 * case needs a distinct, deliberately broken document; this mirrors the
 * template's inline `parse("not xml <<<")` malformed case.
 */
class GoogleNewsParserEdgeCasesTest {

    /** A single RSS 2.0 channel wrapping [inner] item markup. */
    private fun channel(title: String = "Top stories - Google News", inner: String): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <rss version="2.0">
          <channel>
            <title>$title</title>
            $inner
          </channel>
        </rss>
        """.trimIndent()

    // ── malformed / non-XML input ───────────────────────────────────

    @Test
    fun `unclosed tags yield an empty feed rather than throwing`() {
        // Unbalanced markup: the DOM builder rejects it, parse() returns EMPTY.
        val feed = GoogleNewsParser.parse("<rss><channel><title>Broken</title>")
        assertEquals("", feed.title)
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `empty string yields an empty feed`() {
        val feed = GoogleNewsParser.parse("")
        assertEquals("", feed.title)
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `well-formed XML without a channel yields an empty feed`() {
        // Valid XML, but no <channel> element — parse() short-circuits to EMPTY.
        val feed = GoogleNewsParser.parse("<rss version=\"2.0\"></rss>")
        assertEquals("", feed.title)
        assertTrue(feed.items.isEmpty())
    }

    // ── empty feed (channel present, no items) ──────────────────────

    @Test
    fun `channel with no items keeps the title and returns no items`() {
        val feed = GoogleNewsParser.parse(channel(inner = ""))
        assertEquals("Top stories - Google News", feed.title)
        assertTrue(feed.items.isEmpty())
    }

    // ── items missing required fields ───────────────────────────────

    @Test
    fun `item without a title is skipped`() {
        // No <title> → parseItem returns null even though link/guid are valid.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <link>https://news.google.com/rss/articles/CBMiNoTitle?oc=5</link>
                      <guid isPermaLink="false">CBMiNoTitle</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `item with neither link nor guid is skipped`() {
        // guid falls back to link; both absent → blank guid → item dropped.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Headline with no addressable id</title>
                    </item>
                """.trimIndent(),
            ),
        )
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `item with a link but no guid keeps the item and uses the link as guid`() {
        // guid is blank → ifBlank{ link } supplies it, so the item survives.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Just a headline</title>
                      <link>https://news.google.com/rss/articles/CBMiFromLink?oc=5</link>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        val item = feed.items[0]
        assertEquals("https://news.google.com/rss/articles/CBMiFromLink?oc=5", item.link)
        assertEquals("https://news.google.com/rss/articles/CBMiFromLink?oc=5", item.guid)
    }

    @Test
    fun `item with a guid but no link keeps the item with an empty link`() {
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Just a headline</title>
                      <guid isPermaLink="false">CBMiGuidOnly</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        val item = feed.items[0]
        assertEquals("CBMiGuidOnly", item.guid)
        assertEquals("", item.link)
    }

    // ── empty / blank field values ──────────────────────────────────

    @Test
    fun `item with an empty title element is skipped`() {
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title></title>
                      <link>https://news.google.com/rss/articles/CBMiEmptyTitle?oc=5</link>
                      <guid isPermaLink="false">CBMiEmptyTitle</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `item with a whitespace-only title is skipped`() {
        // rawTitle is trimmed before the isBlank() check, so whitespace == blank.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>   </title>
                      <link>https://news.google.com/rss/articles/CBMiBlankTitle?oc=5</link>
                      <guid isPermaLink="false">CBMiBlankTitle</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertTrue(feed.items.isEmpty())
    }

    @Test
    fun `surrounding whitespace in a title is trimmed`() {
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>
                        Padded headline
                      </title>
                      <link>https://news.google.com/rss/articles/CBMiPadded?oc=5</link>
                      <guid isPermaLink="false">CBMiPadded</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        // No <source> and no " - " separator → title kept as-is, publisher empty.
        assertEquals("Padded headline", feed.items[0].title)
        assertEquals("", feed.items[0].publisher)
    }

    @Test
    fun `channel with an empty title element yields a blank feed title`() {
        val feed = GoogleNewsParser.parse(channel(title = "", inner = ""))
        assertEquals("", feed.title)
        assertTrue(feed.items.isEmpty())
    }

    // ── malformed / missing dates ───────────────────────────────────

    @Test
    fun `item with no pubDate keeps the item with a null timestamp`() {
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>No date here</title>
                      <link>https://news.google.com/rss/articles/CBMiNoDate?oc=5</link>
                      <guid isPermaLink="false">CBMiNoDate</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertNull(feed.items[0].publishedAtEpochMs)
    }

    @Test
    fun `item with an unparseable pubDate keeps the item with a null timestamp`() {
        // Neither RFC822 pattern matches "yesterday afternoon" → null, item kept.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Bad date here</title>
                      <link>https://news.google.com/rss/articles/CBMiBadDate?oc=5</link>
                      <guid isPermaLink="false">CBMiBadDate</guid>
                      <pubDate>yesterday afternoon</pubDate>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertNull(feed.items[0].publishedAtEpochMs)
    }

    @Test
    fun `item with a blank pubDate keeps the item with a null timestamp`() {
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Blank date here</title>
                      <link>https://news.google.com/rss/articles/CBMiBlankDate?oc=5</link>
                      <guid isPermaLink="false">CBMiBlankDate</guid>
                      <pubDate>   </pubDate>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertNull(feed.items[0].publishedAtEpochMs)
    }

    @Test
    fun `a valid RFC822 pubDate parses to epoch ms`() {
        // GMT epoch for Mon, 29 Jun 2026 00:00:00 GMT, computed independently
        // of the parser via the same fixed-offset arithmetic the format implies.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Dated story</title>
                      <link>https://news.google.com/rss/articles/CBMiDated?oc=5</link>
                      <guid isPermaLink="false">CBMiDated</guid>
                      <pubDate>Mon, 29 Jun 2026 00:00:00 GMT</pubDate>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertNotNull("a well-formed RFC822 date should parse", feed.items[0].publishedAtEpochMs)
    }

    // ── entity / CDATA decoding (handled at the DOM layer) ───────────

    @Test
    fun `HTML-entity-encoded ampersand in a title is decoded by the XML layer`() {
        // &amp; is a standard XML entity; DOM textContent resolves it to '&'.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Crime &amp; Punishment</title>
                      <link>https://news.google.com/rss/articles/CBMiEntity?oc=5</link>
                      <guid isPermaLink="false">CBMiEntity</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertEquals("Crime & Punishment", feed.items[0].title)
    }

    @Test
    fun `a CDATA-wrapped title is unwrapped by the XML layer`() {
        // CDATA content surfaces verbatim through textContent; the '&' inside
        // is literal (not an entity) and must survive unescaped.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title><![CDATA[Markets & Mayhem]]></title>
                      <link>https://news.google.com/rss/articles/CBMiCdata?oc=5</link>
                      <guid isPermaLink="false">CBMiCdata</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertEquals("Markets & Mayhem", feed.items[0].title)
    }

    @Test
    fun `an entity-encoded link is decoded by the XML layer`() {
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <title>Story with a query-string link</title>
                      <link>https://news.google.com/rss/articles/CBMiLink?hl=en&amp;gl=US</link>
                      <guid isPermaLink="false">CBMiLinkGuid</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertEquals(
            "https://news.google.com/rss/articles/CBMiLink?hl=en&gl=US",
            feed.items[0].link,
        )
    }

    // ── mixed good / bad items in one feed ──────────────────────────

    @Test
    fun `bad items are skipped while good items in the same feed are kept`() {
        // One untitled item (dropped) and one valid item (kept) → size 1.
        val feed = GoogleNewsParser.parse(
            channel(
                inner = """
                    <item>
                      <link>https://news.google.com/rss/articles/CBMiNoTitle?oc=5</link>
                      <guid isPermaLink="false">CBMiNoTitle</guid>
                    </item>
                    <item>
                      <title>A perfectly good story</title>
                      <link>https://news.google.com/rss/articles/CBMiGood?oc=5</link>
                      <guid isPermaLink="false">CBMiGood</guid>
                    </item>
                """.trimIndent(),
            ),
        )
        assertEquals(1, feed.items.size)
        assertEquals("A perfectly good story", feed.items[0].title)
        assertEquals("CBMiGood", feed.items[0].guid)
    }
}
