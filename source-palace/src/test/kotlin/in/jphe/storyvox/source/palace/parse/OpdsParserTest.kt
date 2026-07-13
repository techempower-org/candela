package `in`.jphe.storyvox.source.palace.parse

import `in`.jphe.storyvox.source.palace.pickOpenAccessEpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #502 — fixture-backed coverage for the OPDS 1.x parser.
 *
 * Why Robolectric: the parser uses `android.util.Xml.newPullParser()`,
 * which isn't on the plain-JUnit classpath. Same posture
 * `:source-rss`'s fixture tests take (#464).
 *
 * The fixtures cover three shapes:
 *  - **Acquisition feed** with open-access (free EPUB) entries
 *  - **Mixed acquisition feed** — open-access + LCP-DRM'd entries
 *  - **Navigation feed** — sub-feed links only, no publications
 */
@RunWith(RobolectricTestRunner::class)
class OpdsParserTest {

    @Test
    fun `parses an acquisition feed with open-access EPUB entries`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom"
                  xmlns:opds="http://opds-spec.org/2010/catalog">
              <id>urn:test:open-access-feed</id>
              <title>Sample Library — Open Access</title>
              <link rel="self" href="https://lib.example/opds" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
              <link rel="next" href="https://lib.example/opds?page=2"/>
              <entry>
                <id>urn:librarysimplified.org:works/1234</id>
                <title>The Public Domain Book</title>
                <author><name>Ada Author</name></author>
                <summary>A free-to-read classic.</summary>
                <category term="fiction" label="Fiction"/>
                <link rel="http://opds-spec.org/image"
                      type="image/jpeg"
                      href="https://lib.example/covers/1234.jpg"/>
                <link rel="http://opds-spec.org/acquisition/open-access"
                      type="application/epub+zip"
                      href="https://lib.example/works/1234/download.epub"/>
              </entry>
            </feed>
        """.trimIndent()

        val feed = OpdsParser.parse(xml, baseUrl = "https://lib.example/opds")

        assertEquals("Sample Library — Open Access", feed.title)
        assertEquals("urn:test:open-access-feed", feed.id)
        assertEquals("https://lib.example/opds?page=2", feed.nextHref)
        assertEquals(1, feed.entries.size)

        val entry = feed.entries[0]
        assertEquals("urn:librarysimplified.org:works/1234", entry.id)
        assertEquals("The Public Domain Book", entry.title)
        assertEquals("Ada Author", entry.author)
        assertEquals("A free-to-read classic.", entry.summary)
        assertEquals(listOf("Fiction"), entry.categories)
        assertEquals("https://lib.example/covers/1234.jpg", entry.coverUrl)

        val openAccess = pickOpenAccessEpub(entry.links)
        assertNotNull(
            "Open-access EPUB acquisition link should be picked",
            openAccess,
        )
        assertEquals("https://lib.example/works/1234/download.epub", openAccess!!.href)
    }

    @Test
    fun `flags LCP-DRM entries as non-open-access`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Sample Library — Mixed</title>
              <entry>
                <id>urn:librarysimplified.org:works/9001</id>
                <title>Borrow-Only Bestseller</title>
                <author><name>Bea Author</name></author>
                <link rel="http://opds-spec.org/acquisition/borrow"
                      type="application/vnd.readium.lcp.license.v1.0+json"
                      href="https://lib.example/works/9001/borrow"/>
              </entry>
            </feed>
        """.trimIndent()

        val feed = OpdsParser.parse(xml, baseUrl = "https://lib.example/opds")
        assertEquals(1, feed.entries.size)
        val drm = feed.entries[0]
        // The LCP entry IS a publication (it carries an acquisition
        // rel) — the parser should not drop it. But the open-access
        // picker MUST return null so storyvox doesn't try to download
        // an LCP license payload as if it were an EPUB.
        assertNull(
            "LCP-DRM entry must not produce an open-access EPUB link",
            pickOpenAccessEpub(drm.links),
        )
    }

    @Test
    fun `parses a navigation feed into navLinks`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Sample Library — Root</title>
              <entry>
                <id>nav:featured</id>
                <title>Featured</title>
                <link rel="subsection"
                      type="application/atom+xml;profile=opds-catalog;kind=acquisition"
                      href="https://lib.example/opds/featured"/>
              </entry>
              <entry>
                <id>nav:new-releases</id>
                <title>New Releases</title>
                <link rel="subsection"
                      type="application/atom+xml;profile=opds-catalog;kind=acquisition"
                      href="https://lib.example/opds/new"/>
              </entry>
            </feed>
        """.trimIndent()

        val feed = OpdsParser.parse(xml, baseUrl = "https://lib.example/opds")
        assertEquals("Sample Library — Root", feed.title)
        assertTrue("Pure-navigation feed has no publications", feed.entries.isEmpty())
        assertEquals(2, feed.navLinks.size)
        assertEquals("Featured", feed.navLinks[0].title)
        assertEquals("https://lib.example/opds/featured", feed.navLinks[0].href)
        assertEquals("New Releases", feed.navLinks[1].title)
    }

    @Test
    fun `resolves relative hrefs against the feed base URL`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Library With Relative Paths</title>
              <entry>
                <id>urn:librarysimplified.org:works/42</id>
                <title>Tales of the Relative Link</title>
                <link rel="http://opds-spec.org/acquisition/open-access"
                      type="application/epub+zip"
                      href="/works/42/download.epub"/>
              </entry>
            </feed>
        """.trimIndent()

        val feed = OpdsParser.parse(
            xml,
            baseUrl = "https://lib.example/opds/featured",
        )
        val link = feed.entries[0].links[0]
        assertEquals(
            "https://lib.example/works/42/download.epub",
            link.href,
        )
    }

    @Test
    fun `html content summary strips tags and decodes entities (#1628)`() {
        // OPDS `<content type="html">` carries escaped markup. The old
        // local stripHtml stripped tags but NEVER decoded entities, so
        // `&amp;` leaked into the browse-card subtitle. Field cleanup now
        // uses the shared htmlToInlineText (tag-strip + full entity decode).
        // Content below also pins R1: the old stripHtml replaced a tag with
        // a SPACE, so an intra-word tag ("Sky<i>net</i>rises") split into
        // three words; the shared util merges it ("Skynetrises").
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Sample Library</title>
              <entry>
                <id>urn:test:works/7777</id>
                <title>Entity Test Book</title>
                <author><name>Ent Author</name></author>
                <content type="html">Sky&lt;i&gt;net&lt;/i&gt;rises &amp;amp; wit</content>
                <link rel="http://opds-spec.org/acquisition/open-access"
                      type="application/epub+zip"
                      href="https://lib.example/works/7777/download.epub"/>
              </entry>
            </feed>
        """.trimIndent()

        val feed = OpdsParser.parse(xml, baseUrl = "https://lib.example/opds")
        assertEquals("Skynetrises & wit", feed.entries.single().summary)
    }

    @Test(expected = OpdsParseException::class)
    fun `rejects non-OPDS root element`() {
        // RSS root → not an OPDS feed → throw at parse-time so the
        // source-side surface can flip it to FictionResult.NetworkError
        // with a clear "library is not OPDS 1.x" message.
        OpdsParser.parse(
            xml = """<rss version="2.0"><channel><title>X</title></channel></rss>""",
            baseUrl = "https://lib.example/feed",
        )
    }
}
