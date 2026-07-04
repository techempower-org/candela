package `in`.jphe.storyvox.source.reddit

import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.reddit.config.RedditDefaults
import `in`.jphe.storyvox.source.reddit.net.RedditApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #1492 — RedditSource mapping over the OAuth JSON API, driven by
 * a MockWebServer that serves both the token mint and every read endpoint.
 */
class RedditSourceTest {

    private lateinit var server: MockWebServer
    private val tokenMints = AtomicInteger(0)

    @Before fun setUp() {
        tokenMints.set(0)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("access_token") -> {
                        tokenMints.incrementAndGet()
                        ok(TOKEN_JSON)
                    }
                    path.contains("/subreddits/") -> ok(SUBREDDIT_LISTING_JSON)
                    path.contains("/about") -> ok(ABOUT_JSON)
                    path.contains("/comments/") -> ok(POST_WITH_COMMENTS_JSON)
                    // /r/<sub>/hot | /new | /top
                    Regex("/r/[^/]+/(hot|new|top)").containsMatchIn(path) -> ok(POSTS_JSON)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After fun tearDown() = server.shutdown()

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    private fun source(
        appendTopComments: Boolean = false,
        favorites: List<String> = emptyList(),
    ): RedditSource {
        val config = fakeRedditConfig(
            host = server.url("/").toString(),
            appendTopComments = appendTopComments,
            favorites = favorites,
        )
        return RedditSource(RedditApi(testClient(), config), config)
    }

    @Test fun `popular maps subreddits to fictions`() = runBlocking {
        val page = (source().popular(1) as FictionResult.Success).value
        assertEquals(2, page.items.size)
        assertEquals("reddit:books", page.items[0].id)
        assertEquals("r/books", page.items[0].title)
        assertEquals("reddit:nosleep", page.items[1].id)
        // Discovery is single-cursor-page; no false "load more".
        assertFalse(page.hasNext)
    }

    @Test fun `popular page two is empty without a second fetch`() = runBlocking {
        val page = (source().popular(2) as FictionResult.Success).value
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasNext)
    }

    @Test fun `search maps subreddits`() = runBlocking {
        val page = (source().search(SearchQuery(term = "book")) as FictionResult.Success).value
        assertEquals(2, page.items.size)
    }

    @Test fun `blank search returns empty without hitting the network`() = runBlocking {
        val result = source().search(SearchQuery(term = "   "))
        val page = (result as FictionResult.Success).value
        assertTrue(page.items.isEmpty())
        assertEquals(0, tokenMints.get()) // never even minted a token
    }

    @Test fun `fictionDetail builds a chapter per post`() = runBlocking {
        val detail = (source().fictionDetail("reddit:books") as FictionResult.Success).value
        assertEquals("r/books", detail.summary.title)
        assertEquals(2, detail.chapters.size)
        assertEquals("reddit:books::abc123", detail.chapters[0].id)
        assertEquals("abc123", detail.chapters[0].sourceChapterId)
        assertEquals("A great book thread", detail.chapters[0].title)
        assertEquals(0, detail.chapters[0].index)
        assertEquals(2, detail.summary.chapterCount)
    }

    @Test fun `chapter returns full body without comments by default`() = runBlocking {
        val content = (
            source(appendTopComments = false)
                .chapter("reddit:books", "reddit:books::abc123") as FictionResult.Success
            ).value
        assertTrue(content.plainBody.contains("The full body of the post."))
        assertFalse(content.plainBody.contains("Top comments"))
        assertEquals("abc123", content.info.sourceChapterId)
    }

    @Test fun `chapter appends top comments when enabled`() = runBlocking {
        val content = (
            source(appendTopComments = true)
                .chapter("reddit:books", "reddit:books::abc123") as FictionResult.Success
            ).value
        assertTrue(content.plainBody.contains("Top comments:"))
        assertTrue(content.plainBody.contains("commenter1"))
        // Blank-body comment is dropped, not rendered as an empty line.
        assertFalse(content.plainBody.contains("u/unknown:"))
    }

    @Test fun `link post chapter degrades to a link note`() = runBlocking {
        // def456 is the link post in POSTS_JSON, but the comments fixture
        // serves the self post — swap the dispatcher for this one case.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("access_token") -> ok(TOKEN_JSON)
                    path.contains("/comments/") -> ok(
                        """[{"kind":"Listing","data":{"children":[
                           {"kind":"t3","data":{"id":"def456","title":"Link","selftext":"",
                            "url":"https://example.com/x"}}]}},
                           {"kind":"Listing","data":{"children":[]}}]""".trimIndent(),
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val content = (
            source().chapter("reddit:books", "reddit:books::def456") as FictionResult.Success
            ).value
        assertTrue(content.plainBody.contains("link post"))
        assertTrue(content.plainBody.contains("https://example.com/x"))
    }

    @Test fun `byGenre resolves a subreddit name to one fiction`() = runBlocking {
        val page = (source().byGenre("books", 1) as FictionResult.Success).value
        assertEquals(1, page.items.size)
        assertEquals("reddit:books", page.items[0].id)
    }

    @Test fun `byGenre normalises an r-slash prefix`() = runBlocking {
        val page = (source().byGenre("r/books", 1) as FictionResult.Success).value
        assertEquals(1, page.items.size)
        assertEquals("reddit:books", page.items[0].id)
    }

    @Test fun `genres returns configured favourites`() = runBlocking {
        val list = (source(favorites = listOf("scifi", "fantasy")).genres() as FictionResult.Success).value
        assertEquals(listOf("scifi", "fantasy"), list)
    }

    @Test fun `genres falls back to curated defaults`() = runBlocking {
        val list = (source().genres() as FictionResult.Success).value
        assertEquals(RedditDefaults.DEFAULT_SUBREDDITS, list)
    }

    @Test fun `bearer token is minted once and reused across calls`() = runBlocking {
        val src = source()
        src.popular(1)
        src.fictionDetail("reddit:books")
        // Two API-bearing operations, one token mint.
        assertEquals(1, tokenMints.get())
    }

    @Test fun `missing client id fast-fails with AuthRequired`() = runBlocking {
        val config = fakeRedditConfig(host = server.url("/").toString(), clientId = "")
        val src = RedditSource(RedditApi(testClient(), config), config)
        val result = src.popular(1)
        assertTrue(result is FictionResult.AuthRequired)
        assertEquals(0, tokenMints.get()) // never attempted a mint
    }
}
