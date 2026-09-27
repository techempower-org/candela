package `in`.jphe.storyvox.source.bookshare

import `in`.jphe.storyvox.data.source.FictionSourceIdResolver
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.source.bookshare.net.BookshareApi
import `in`.jphe.storyvox.source.bookshare.net.BookshareAuthor
import `in`.jphe.storyvox.source.bookshare.net.BookshareCategory
import `in`.jphe.storyvox.source.bookshare.net.BookshareLink
import `in`.jphe.storyvox.source.bookshare.net.BookshareTitle
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1462 — the pieces that must be right the moment a partner key
 * arrives: prefixed fiction ids (routing), cursor paging, and title metadata.
 * Pure JVM; no network.
 */
class BookshareKeyReadinessTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `fiction ids carry the bookshare prefix and round-trip`() {
        assertEquals("bookshare:1041744", fictionIdOf(1041744L))
        assertEquals(1041744L, bookshareIdOf("bookshare:1041744"))
        assertEquals(1041744L, bookshareIdOf("1041744"))
        assertNull(bookshareIdOf("gutenberg:84"))
        assertNull(bookshareIdOf("bookshare:"))
        assertNull(bookshareIdOf("bookshare:0"))
    }

    @Test
    fun `prefixed id resolves to bookshare, not royal road`() {
        val bound = setOf(SourceIds.BOOKSHARE, SourceIds.ROYAL_ROAD)
        assertEquals(
            SourceIds.BOOKSHARE,
            FictionSourceIdResolver.resolve(fictionIdOf(42L), bound),
        )
    }

    @Test
    fun `cursor for page two is the next token page one returned`() {
        val cursors = BookshareCursors()
        val q = BookshareCursors.queryKey("tom", null, null)
        assertNull(cursors.startFor(q, 2))
        cursors.record(q, page = 1, next = "tok-2")
        cursors.record(q, page = 2, next = "tok-3")
        assertEquals("tok-2", cursors.startFor(q, 2))
        assertEquals("tok-3", cursors.startFor(q, 3))
    }

    @Test
    fun `cursors are per query and a null next clears the slot`() {
        val cursors = BookshareCursors()
        val a = BookshareCursors.queryKey("tom", null, null)
        val b = BookshareCursors.queryKey(null, null, "Fiction")
        cursors.record(a, 1, "a2")
        assertNull(cursors.startFor(b, 2))
        cursors.record(a, 1, null)
        assertNull(cursors.startFor(a, 2))
    }

    @Test
    fun `cursor store is bounded`() {
        val cursors = BookshareCursors(maxEntries = 2)
        cursors.record("q", 1, "t2")
        cursors.record("q", 2, "t3")
        cursors.record("q", 3, "t4")
        assertNull(cursors.startFor("q", 2))
        assertEquals("t4", cursors.startFor("q", 4))
    }

    @Test
    fun `titlePath targets the title metadata endpoint`() {
        assertEquals("/v2/titles/1041744?api_key=K%2B1", BookshareApi.titlePath(1041744L, "K+1"))
    }

    @Test
    fun `decodes title metadata with synopsis and cover links`() {
        val body = """
            {"bookshareId":1041744,"title":"The Adventures of Tom Sawyer","subtitle":null,
             "authors":[{"firstName":"Mark","lastName":"Twain"}],
             "synopsis":"A boy on the Mississippi.",
             "categories":[{"name":"Fiction"}],
             "links":[{"rel":"thumbnail","href":"https://x/t.jpg"},
                      {"rel":"coverimage","href":"https://x/c.jpg"}],
             "formats":[{"formatId":"DAISY"}]}
        """.trimIndent()
        val t = json.decodeFromString<BookshareTitle>(body)
        assertEquals("A boy on the Mississippi.", t.synopsis)
        assertEquals("https://x/c.jpg", t.coverUrl())
    }

    @Test
    fun `thumbnail is the cover fallback`() {
        val t = BookshareTitle(links = listOf(BookshareLink("thumbnail", "https://x/t.jpg")))
        assertEquals("https://x/t.jpg", t.coverUrl())
        assertNull(BookshareTitle().coverUrl())
    }

    @Test
    fun `detail maps metadata with an empty chapter list`() {
        val detail = BookshareTitle(
            bookshareId = 7L,
            title = "Moby Dick",
            authors = listOf(BookshareAuthor("Herman", "Melville")),
            synopsis = "A whale.",
            categories = listOf(BookshareCategory("Fiction")),
        ).toDetail()
        assertEquals("bookshare:7", detail.summary.id)
        assertEquals(SourceIds.BOOKSHARE, detail.summary.sourceId)
        assertEquals("A whale.", detail.summary.description)
        assertEquals(FictionStatus.COMPLETED, detail.summary.status)
        assertEquals(listOf("Fiction"), detail.genres)
        assertTrue(detail.chapters.isEmpty())
    }
}
