package `in`.jphe.storyvox.playback.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1232 — locks the Android Auto media-id grammar. The browse service
 * builds these strings and the session callback parses them; a drift here means
 * "tap a book → nothing plays", so the round-trip is pinned tight.
 */
class AutoMediaIdTest {

    @Test
    fun `root and resume parse to their singletons`() {
        assertEquals(AutoMediaId.Node.Root, AutoMediaId.parse("/"))
        assertEquals(AutoMediaId.Node.Resume, AutoMediaId.parse("/resume"))
    }

    @Test
    fun `bare categories parse to Category`() {
        assertEquals(AutoMediaId.Node.Category("/library"), AutoMediaId.parse("/library"))
        assertEquals(AutoMediaId.Node.Category("/follows"), AutoMediaId.parse("/follows"))
        assertEquals(AutoMediaId.Node.Category("/recent"), AutoMediaId.parse("/recent"))
        assertEquals(AutoMediaId.Node.Category("/new"), AutoMediaId.parse("/new"))
    }

    @Test
    fun `book id round-trips`() {
        val id = AutoMediaId.book(AutoMediaId.LIBRARY, "rr-12345")
        assertEquals("/library/rr-12345", id)
        assertEquals(
            AutoMediaId.Node.Book("/library", "rr-12345"),
            AutoMediaId.parse(id),
        )
    }

    @Test
    fun `chapter id round-trips`() {
        val id = AutoMediaId.chapter(AutoMediaId.RECENT, "fic1", "chap7")
        assertEquals("/recent/fic1/chap7", id)
        assertEquals(
            AutoMediaId.Node.Chapter("/recent", "fic1", "chap7"),
            AutoMediaId.parse(id),
        )
    }

    @Test
    fun `ids containing slashes survive the round-trip`() {
        // Some sources mint composite chapter ids with '/'. Encoding must keep
        // them in a single segment so parse doesn't mistake them for depth.
        val fiction = "ao3/works/42"
        val chapter = "ch/3"
        val id = AutoMediaId.chapter(AutoMediaId.LIBRARY, fiction, chapter)
        val node = AutoMediaId.parse(id)
        assertEquals(
            AutoMediaId.Node.Chapter("/library", fiction, chapter),
            node,
        )
    }

    @Test
    fun `ids containing a literal percent-2F survive`() {
        val fiction = "weird%2Fid"
        val id = AutoMediaId.book(AutoMediaId.FOLLOWS, fiction)
        assertEquals(
            AutoMediaId.Node.Book("/follows", fiction),
            AutoMediaId.parse(id),
        )
    }

    @Test
    fun `unknown or malformed ids parse to null`() {
        assertNull(AutoMediaId.parse(""))
        assertNull(AutoMediaId.parse("/bogus"))
        assertNull(AutoMediaId.parse("/library/a/b/c")) // too deep
        assertNull(AutoMediaId.parse("library")) // missing leading slash category
    }

    @Test
    fun `root categories are exactly the four Auto tabs`() {
        assertEquals(
            listOf("/library", "/follows", "/recent", "/new"),
            AutoMediaId.ROOT_CATEGORIES,
        )
        assertTrue(AutoMediaId.ROOT_CATEGORIES.size <= 4)
    }

    @Test
    fun `only library and follows are book categories`() {
        assertTrue(AutoMediaId.isBookCategory(AutoMediaId.LIBRARY))
        assertTrue(AutoMediaId.isBookCategory(AutoMediaId.FOLLOWS))
        assertTrue(!AutoMediaId.isBookCategory(AutoMediaId.RECENT))
        assertTrue(!AutoMediaId.isBookCategory(AutoMediaId.NEW))
    }
}
