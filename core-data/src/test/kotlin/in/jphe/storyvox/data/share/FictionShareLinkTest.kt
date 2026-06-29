package `in`.jphe.storyvox.data.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1313 — fiction share-link build/parse round-trips. Pure (no Android
 * types), so it runs as a plain JUnit test.
 */
class FictionShareLinkTest {

    @Test
    fun roundTrip_prefixedId() {
        val id = "gutenberg:84"
        val link = FictionShareLink.build(id)
        assertEquals("candela://fiction/gutenberg%3A84", link)
        assertEquals(id, FictionShareLink.parse(link))
    }

    @Test
    fun roundTrip_bareNumericRoyalRoadId() {
        val id = "123456"
        assertEquals("candela://fiction/123456", FictionShareLink.build(id))
        assertEquals(id, FictionShareLink.parse(FictionShareLink.build(id)))
    }

    @Test
    fun roundTrip_idWithSlash_isPreservedInOneSegment() {
        // discord:guild/channel — the '/' must be encoded so it stays one
        // path segment and decodes back intact.
        val id = "discord:111/222"
        val link = FictionShareLink.build(id)
        assertEquals("candela://fiction/discord%3A111%2F222", link)
        assertEquals(id, FictionShareLink.parse(link))
    }

    @Test
    fun roundTrip_epubHashId() {
        val id = "epub:9469520f"
        assertEquals(id, FictionShareLink.parse(FictionShareLink.build(id)))
    }

    @Test
    fun parse_isCaseInsensitiveOnSchemeAndHost() {
        assertEquals("gutenberg:84", FictionShareLink.parse("CANDELA://Fiction/gutenberg%3A84"))
    }

    @Test
    fun parse_toleratesTrailingSlashAndQueryAndFragment() {
        assertEquals("ao3:42", FictionShareLink.parse("candela://fiction/ao3%3A42/"))
        assertEquals("ao3:42", FictionShareLink.parse("candela://fiction/ao3%3A42?utm=x"))
        assertEquals("ao3:42", FictionShareLink.parse("candela://fiction/ao3%3A42#frag"))
    }

    @Test
    fun parse_rejectsNonFictionLinks() {
        assertNull(FictionShareLink.parse("https://royalroad.com/fiction/123"))
        assertNull(FictionShareLink.parse("candela://reader/abc"))
        assertNull(FictionShareLink.parse("candela://fiction/")) // no id
        assertNull(FictionShareLink.parse("storyvox://fiction/123"))
        assertNull(FictionShareLink.parse(""))
        assertNull(FictionShareLink.parse("not a uri at all"))
    }
}
