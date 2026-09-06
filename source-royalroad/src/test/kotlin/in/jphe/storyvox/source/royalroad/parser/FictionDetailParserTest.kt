package `in`.jphe.storyvox.source.royalroad.parser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1628 — pins the RoyalRoad JSON-LD `description` cleanup after
 * moving it from a local 5-entity table (`&nbsp;/&amp;/&#x2013;/&quot;/
 * &#x27;`, tags stripped to the empty string, internal whitespace kept)
 * to the shared [stripHtmlTags] → `htmlToInlineText`.
 *
 * Tag-strip + those five entities are behaviour-preserving on a single-line
 * blurb; the wins are (a) the full entity table (curly quotes / hex / accents
 * the old table missed) and (b) multi-line blurbs collapsing to one line —
 * which ALIGNS the JSON-LD path with the parser's `.text()` HTML fallback.
 */
class FictionDetailParserTest {

    @Test
    fun `stripHtmlTags decodes entities and strips inline tags`() {
        assertEquals(
            "A tale of woe & wonder–unabridged.'s end",
            FictionDetailParser.stripHtmlTags(
                "A tale of <i>woe</i> &amp; wonder&#x2013;unabridged.&#x27;s end",
            ),
        )
    }

    @Test
    fun `stripHtmlTags collapses multi-line blurbs to one line`() {
        // Aligns the JSON-LD description path with the div.hidden-content
        // .text() fallback (which already collapses).
        assertEquals(
            "Line one. Line two.",
            FictionDetailParser.stripHtmlTags("Line one.\nLine two."),
        )
    }
}
