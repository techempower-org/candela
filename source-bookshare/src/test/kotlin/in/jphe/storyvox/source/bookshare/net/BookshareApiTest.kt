package `in`.jphe.storyvox.source.bookshare.net

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.source.bookshare.toListPage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1002 — Bookshare API v2 URL building + response parsing. Pure JVM
 * (kotlinx.serialization), no network or Robolectric: validates the wire-type
 * contracts against documented response shapes and the summary mapping.
 */
class BookshareApiTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    @Test
    fun `titlesPath builds a title query with limit and api_key`() {
        val path = BookshareApi.titlesPath(
            title = "tom sawyer", author = null, category = null,
            limit = 10, start = null, apiKey = "KEY",
        )
        assertEquals("/v2/titles?title=tom+sawyer&limit=10&api_key=KEY", path)
    }

    @Test
    fun `titlesPath includes author, category and start when present`() {
        val path = BookshareApi.titlesPath(
            title = null, author = "twain", category = "Fiction",
            limit = 24, start = "tok123", apiKey = "K",
        )
        assertEquals(
            "/v2/titles?author=twain&categories=Fiction&limit=24&start=tok123&api_key=K",
            path,
        )
    }

    @Test
    fun `decodes a titles search response`() {
        val body = """
            {"totalResults":179,"limit":10,"next":"abcde","titles":[
              {"bookshareId":1041744,"title":"The Adventures of Tom Sawyer",
               "authors":[{"firstName":"Mark","lastName":"Twain"}],
               "isbn13":"9781551998121","categories":[{"name":"Fiction"}]}
            ]}
        """.trimIndent()
        val page = json.decodeFromString<BookshareTitlesPage>(body)
        assertEquals(179, page.totalResults)
        assertEquals("abcde", page.next)
        assertEquals(1, page.titles.size)
        val t = page.titles.first()
        assertEquals(1041744L, t.bookshareId)
        assertEquals("The Adventures of Tom Sawyer", t.title)
        assertEquals("Mark Twain", t.authorDisplay())
    }

    @Test
    fun `decodes a categories response`() {
        val body = """{"categories":[{"name":"Fiction","description":"x"},{"name":"Biography"}]}"""
        val page = json.decodeFromString<BookshareCategoriesPage>(body)
        assertEquals(listOf("Fiction", "Biography"), page.categories.map { it.name })
    }

    @Test
    fun `author display handles missing name parts`() {
        assertEquals("Mark Twain", BookshareAuthor("Mark", "Twain").display())
        assertEquals("Twain", BookshareAuthor(null, "Twain").display())
        assertEquals("Unknown author", BookshareTitle(title = "X").authorDisplay())
    }

    @Test
    fun `maps a titles page to source summaries`() {
        val page = BookshareTitlesPage(
            next = "more",
            titles = listOf(
                BookshareTitle(
                    bookshareId = 42L,
                    title = "Moby Dick",
                    authors = listOf(BookshareAuthor("Herman", "Melville")),
                    categories = listOf(BookshareCategory("Fiction")),
                ),
            ),
        )
        val listPage = page.toListPage(page = 2)
        assertEquals(2, listPage.page)
        assertTrue(listPage.hasNext)
        val summary = listPage.items.single()
        assertEquals("42", summary.id)
        assertEquals(SourceIds.BOOKSHARE, summary.sourceId)
        assertEquals("Moby Dick", summary.title)
        assertEquals("Herman Melville", summary.author)
        assertEquals(listOf("Fiction"), summary.tags)
    }
}
