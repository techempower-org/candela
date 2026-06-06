package `in`.jphe.storyvox.feature.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #998 — in-text search. Pins the pure match-finding surface that
 * the reader's find-in-text affordance builds on, so the "search War &
 * Peace and jump to a hit" workflow can't silently regress on a reader
 * refactor.
 *
 * [findMatches] is deliberately pure (no Compose / Android deps) so the
 * core search logic is unit-testable without rendering [ReaderTextView]
 * or standing up Robolectric — same split the codebase already uses for
 * #794's `filterChaptersByQuery`.
 */
class ReaderTextSearchTest {

    @Test
    fun `blank query yields no matches`() {
        assertTrue(findMatches("The quick brown fox", "").isEmpty())
        assertTrue(findMatches("The quick brown fox", "   ").isEmpty())
    }

    @Test
    fun `query with no occurrence yields no matches`() {
        assertTrue(findMatches("The quick brown fox", "zebra").isEmpty())
    }

    @Test
    fun `single match returns one range at the right offset`() {
        // "brown" starts at index 10.
        assertEquals(listOf(10..14), findMatches("The quick brown fox", "brown"))
    }

    @Test
    fun `multiple matches are returned in document order`() {
        val text = "fox fox fox"
        // "fox" at 0, 4, 8 — each length 3 → end-inclusive index +2.
        assertEquals(listOf(0..2, 4..6, 8..10), findMatches(text, "fox"))
    }

    @Test
    fun `matching is case insensitive`() {
        val text = "The The THE the"
        // Every "the" matches regardless of case; positions 0,4,8,12.
        assertEquals(listOf(0..2, 4..6, 8..10, 12..14), findMatches(text, "the"))
    }

    @Test
    fun `overlapping matches do not double-count — advance past each hit`() {
        // "aa" in "aaaa": non-overlapping scan finds 0..1 and 2..3, NOT
        // 1..2. indexOf advancing by match length is the contract.
        assertEquals(listOf(0..1, 2..3), findMatches("aaaa", "aa"))
    }

    @Test
    fun `query is trimmed before matching`() {
        // A stray trailing space from a paste shouldn't zero out results,
        // matching #794's filterChaptersByQuery behaviour.
        assertEquals(listOf(10..14), findMatches("The quick brown fox", "  brown  "))
    }

    @Test
    fun `unicode query matches by code unit offset`() {
        // Accented + CJK content: the returned offsets are UTF-16 char
        // indices (what TextLayoutResult.getLineForOffset consumes), so a
        // multi-byte glyph still yields a usable single-char-unit range.
        val text = "café 第1章 café"
        // All BMP chars (é = U+00E9, 第/章 each one UTF-16 unit), so char
        // index == code-unit index. first "café" at 0..3; the run
        // "café"(4) + " "(1) + "第1章"(3) + " "(1) = 9, so the second at 9..12.
        assertEquals(listOf(0..3, 9..12), findMatches(text, "café"))
    }

    @Test
    fun `empty text yields no matches`() {
        assertTrue(findMatches("", "anything").isEmpty())
    }

    @Test
    fun `whole-text match returns a single full-span range`() {
        assertEquals(listOf(0..2), findMatches("abc", "abc"))
    }

    // --- nextMatchIndex / prevMatchIndex (wraparound cycling) ---------------

    @Test
    fun `next advances and wraps from last to first`() {
        assertEquals(1, nextMatchIndex(current = 0, count = 3))
        assertEquals(2, nextMatchIndex(current = 1, count = 3))
        assertEquals(0, nextMatchIndex(current = 2, count = 3))
    }

    @Test
    fun `prev retreats and wraps from first to last`() {
        assertEquals(2, prevMatchIndex(current = 0, count = 3))
        assertEquals(0, prevMatchIndex(current = 1, count = 3))
        assertEquals(1, prevMatchIndex(current = 2, count = 3))
    }

    @Test
    fun `cycling with no matches stays at zero`() {
        // count == 0 must not divide-by-zero; the affordance is hidden
        // anyway, but the helper has to be total.
        assertEquals(0, nextMatchIndex(current = 0, count = 0))
        assertEquals(0, prevMatchIndex(current = 0, count = 0))
    }

    @Test
    fun `single match cycles to itself`() {
        assertEquals(0, nextMatchIndex(current = 0, count = 1))
        assertEquals(0, prevMatchIndex(current = 0, count = 1))
    }
}
