package `in`.jphe.storyvox.source.wikipedia.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1628 — pins the MediaWiki search-snippet cleanup after moving it
 * from an inline tag-strip (tags only, entities left raw) to the shared
 * [cleanSearchSnippet] → [htmlToInlineText]. Tag-stripping is
 * behaviour-preserving; entity decoding (`&amp;` → `&`) is the FIX the old
 * inline strip left leaking into the browse-card subtitle.
 */
class WikipediaSearchSnippetTest {

    @Test
    fun `strips searchmatch highlight spans`() {
        val snippet = "The <span class=\"searchmatch\">quantum</span> theory of fields"
        assertEquals("The quantum theory of fields", cleanSearchSnippet(snippet))
    }

    @Test
    fun `decodes entities the old inline strip left raw`() {
        val snippet =
            "The <span class=\"searchmatch\">quantum</span> theory &amp; its " +
                "<span class=\"searchmatch\">field</span> &mdash; a curly quote&#8217;s tail"
        assertEquals(
            "The quantum theory & its field — a curly quote’s tail",
            cleanSearchSnippet(snippet),
        )
    }

    @Test
    fun `blank snippet yields empty string`() {
        assertEquals("", cleanSearchSnippet(""))
    }
}
