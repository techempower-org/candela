package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1229 — whole-book ("find in book") search core. Pins the pure
 * aggregation + snippet surface [searchBook] / [buildSnippet] builds on, so the
 * "search the whole book, list the chapters, jump to a hit" workflow can't
 * silently regress on a reader refactor.
 *
 * Pure (no Compose / Room) so the core is unit-testable without rendering the
 * overlay or standing up a database — the same split #998's [findMatches] uses.
 */
class BookTextSearchTest {

    private fun ch(id: String, index: Int, body: String, title: String = "Chapter $index") =
        ChapterBody(chapterId = id, index = index, title = title, body = body)

    // --- searchBook ---------------------------------------------------------

    @Test
    fun `returns one result per matching chapter, in input order`() {
        val chapters = listOf(
            ch("c0", 0, "the dragon sleeps under the mountain"),
            ch("c1", 1, "a quiet village, nothing stirs"),
            ch("c2", 2, "the DRAGON wakes at last"),
        )
        val results = searchBook(chapters, "dragon")
        assertEquals(listOf("c0", "c2"), results.map { it.chapterId })
        assertEquals(listOf(0, 2), results.map { it.chapterIndex })
    }

    @Test
    fun `is case-insensitive`() {
        val results = searchBook(listOf(ch("c0", 0, "The Sword of Aeons")), "SWORD")
        assertEquals(1, results.size)
    }

    @Test
    fun `counts every non-overlapping match in the chapter`() {
        val results = searchBook(listOf(ch("c0", 0, "fox fox fox")), "fox")
        assertEquals(3, results.single().matchCount)
    }

    @Test
    fun `matchOffset is the first occurrence`() {
        val results = searchBook(listOf(ch("c0", 0, "aaa needle bbb needle")), "needle")
        assertEquals("aaa needle bbb needle".indexOf("needle"), results.single().matchOffset)
    }

    @Test
    fun `blank query yields no results`() {
        val chapters = listOf(ch("c0", 0, "anything at all"))
        assertTrue(searchBook(chapters, "").isEmpty())
        assertTrue(searchBook(chapters, "   ").isEmpty())
    }

    @Test
    fun `query is trimmed before matching`() {
        val results = searchBook(listOf(ch("c0", 0, "find the relic here")), "  relic  ")
        assertEquals(1, results.size)
        assertEquals("find the relic here".indexOf("relic"), results.single().matchOffset)
    }

    @Test
    fun `drops a chapter with no literal occurrence (LIKE over-match guard)`() {
        // SQL `LIKE '%100%%'` would return this row (wildcard %), but the body
        // holds no literal "100%", so the literal re-scan drops it.
        val results = searchBook(listOf(ch("c0", 0, "scored 100 points")), "100%")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `empty chapter list yields no results`() {
        assertTrue(searchBook(emptyList(), "anything").isEmpty())
    }

    @Test
    fun `snippet contains the matched term at the reported range`() {
        val results = searchBook(
            listOf(ch("c0", 0, "The quick brown fox jumps over the lazy dog")),
            "brown",
        )
        val r = results.single()
        // End-inclusive range → exclusive end for substring.
        val highlighted = r.snippet.substring(r.snippetMatchRange.first, r.snippetMatchRange.last + 1)
        assertEquals("brown", highlighted)
    }

    @Test
    fun `snippet preserves match casing from the body, not the query`() {
        val results = searchBook(listOf(ch("c0", 0, "The DRAGON roars")), "dragon")
        val r = results.single()
        val highlighted = r.snippet.substring(r.snippetMatchRange.first, r.snippetMatchRange.last + 1)
        assertEquals("DRAGON", highlighted)
    }

    // --- buildSnippet -------------------------------------------------------

    @Test
    fun `short body is returned whole with no ellipsis`() {
        val (snippet, range) = buildSnippet("brown fox", matchStart = 0, matchLength = 5)
        assertEquals("brown fox", snippet)
        assertEquals(0, range.first)
        assertEquals("brown", snippet.substring(range.first, range.last + 1))
    }

    @Test
    fun `long body is clipped with leading and trailing ellipsis around the match`() {
        val body = "a".repeat(60) + "TARGET" + "b".repeat(60)
        val (snippet, range) = buildSnippet(body, matchStart = 60, matchLength = 6, radius = 48)
        assertTrue("expected leading ellipsis", snippet.startsWith("…"))
        assertTrue("expected trailing ellipsis", snippet.endsWith("…"))
        assertEquals("TARGET", snippet.substring(range.first, range.last + 1))
    }

    @Test
    fun `match at body start has no leading ellipsis`() {
        val body = "TARGET" + "x".repeat(80)
        val (snippet, range) = buildSnippet(body, matchStart = 0, matchLength = 6, radius = 48)
        assertTrue(snippet.startsWith("TARGET"))
        assertTrue(snippet.endsWith("…"))
        assertEquals("TARGET", snippet.substring(range.first, range.last + 1))
    }

    @Test
    fun `whitespace and newlines collapse without desyncing the highlight range`() {
        val body = "alpha\n\nbeta   TARGET\tgamma"
        val matchStart = body.indexOf("TARGET")
        val (snippet, range) = buildSnippet(body, matchStart = matchStart, matchLength = 6)
        // No raw newlines/tabs survive in the snippet.
        assertTrue(snippet.none { it == '\n' || it == '\t' })
        assertEquals("TARGET", snippet.substring(range.first, range.last + 1))
    }
}
