package `in`.jphe.storyvox.source.rss.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1489 — RSS/Atom autodiscovery from an HTML page. Pure logic (regex +
 * java.net.URI), plain JUnit — no Android, no XML parser. This is the fix for
 * pasting a bare homepage (`tricycle.org`) instead of the feed URL.
 */
class FeedDiscoveryTest {

    @Test
    fun `discovers an absolute rss href (tricycle-shaped head)`() {
        val html = """
            <!doctype html><html><head>
              <title>Tricycle</title>
              <link rel="alternate" type="application/rss+xml" title="Tricycle » Feed"
                    href="https://tricycle.org/feed/">
            </head><body>…</body></html>
        """.trimIndent()
        assertEquals(
            "https://tricycle.org/feed/",
            discoverFeedUrl(html, "https://tricycle.org/"),
        )
    }

    @Test
    fun `resolves a relative href against the page URL`() {
        val html =
            """<link rel="alternate" type="application/rss+xml" href="/feed/">"""
        assertEquals(
            "https://example.com/feed/",
            discoverFeedUrl(html, "https://example.com/"),
        )
    }

    @Test
    fun `single-quoted attributes are handled`() {
        val html =
            "<link rel='alternate' type='application/rss+xml' href='https://example.com/rss.xml'>"
        assertEquals(
            "https://example.com/rss.xml",
            discoverFeedUrl(html, "https://example.com/"),
        )
    }

    @Test
    fun `returns null when the page advertises no feed`() {
        val html = """
            <head>
              <link rel="stylesheet" href="/style.css">
              <link rel="icon" href="/favicon.ico">
            </head>
        """.trimIndent()
        assertNull(discoverFeedUrl(html, "https://example.com/"))
    }

    @Test
    fun `skips the comments feed when a main feed exists`() {
        // WordPress advertises both; the main feed must win.
        val html = """
            <link rel="alternate" type="application/rss+xml" title="Comments Feed"
                  href="https://blog.example.com/comments/feed/">
            <link rel="alternate" type="application/rss+xml" title="Feed"
                  href="https://blog.example.com/feed/">
        """.trimIndent()
        assertEquals(
            "https://blog.example.com/feed/",
            discoverFeedUrl(html, "https://blog.example.com/"),
        )
    }

    @Test
    fun `discovers an atom feed when that is all the page offers`() {
        val html =
            """<link rel="alternate" type="application/atom+xml" href="https://example.com/atom.xml">"""
        assertEquals(
            "https://example.com/atom.xml",
            discoverFeedUrl(html, "https://example.com/"),
        )
    }

    @Test
    fun `prefers rss over atom when both are advertised`() {
        val html = """
            <link rel="alternate" type="application/atom+xml" href="https://example.com/atom.xml">
            <link rel="alternate" type="application/rss+xml" href="https://example.com/rss.xml">
        """.trimIndent()
        assertEquals(
            "https://example.com/rss.xml",
            discoverFeedUrl(html, "https://example.com/"),
        )
    }

    @Test
    fun `ignores an alternate link that is not a feed type`() {
        // rel=alternate is also used for hreflang / print stylesheets.
        val html =
            """<link rel="alternate" hreflang="fr" href="https://example.com/fr/">"""
        assertNull(discoverFeedUrl(html, "https://example.com/"))
    }
}
