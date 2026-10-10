package `in`.jphe.storyvox.source.github.inbox

import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.filter.FilterValue
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.github.render.MarkdownChapterRenderer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.net.URLDecoder
import java.util.Collections

/** #1470 — GitHub inbox narrator: browse mapping, detail, and both chapters over MockWebServer. */
class GitHubInboxSourceTest {

    private lateinit var server: MockWebServer
    private val paths: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Before fun setUp() {
        paths.clear()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                paths += path
                val body = when {
                    path.startsWith("/search/issues") -> SEARCH
                    path.startsWith("/notifications") -> NOTIFICATIONS
                    path.startsWith("/repos/o/r/releases/42") -> RELEASE
                    path.startsWith("/repos/o/r/issues/7/comments") -> ISSUE_COMMENTS
                    path.startsWith("/repos/o/r/issues/7") -> ISSUE
                    path.startsWith("/repos/o/r/pulls/7/reviews") -> REVIEWS
                    path.startsWith("/repos/o/r/pulls/7/comments") -> REVIEW_COMMENTS
                    path.startsWith("/repos/o/r/pulls/7") -> PULL
                    path.startsWith("/repos/o/r/commits/abc123/check-runs") -> CHECKS
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setResponseCode(200).setBody(body)
            }
        }
        server.start()
    }

    @After fun tearDown() { server.shutdown() }

    private fun source(signedIn: Boolean = true): GitHubInboxSource {
        val host = server.url("/").toString().trimEnd('/')
        val api = object : GitHubInboxApi(OkHttpClient()) {
            override val baseUrl: String get() = host
        }
        return GitHubInboxSource(api, FakeGitHubAuth(signedIn), MarkdownChapterRenderer())
    }

    @Test fun `signed-out user gets AuthRequired with no network call`() {
        val result = runBlocking { source(signedIn = false).latestUpdates(1) }
        assertTrue("got $result", result is FictionResult.AuthRequired)
        assertTrue("expected no requests, got $paths", paths.isEmpty())
    }

    @Test fun `review tab searches review-requested open PRs`() {
        val result = runBlocking { source().latestUpdates(1) }
        val page = (result as FictionResult.Success).value
        val q = URLDecoder.decode(paths.single().substringAfter("q=").substringBefore('&'), "UTF-8")
        assertTrue(q, q.contains("review-requested:@me"))
        assertTrue(q, q.contains("is:open"))
        assertEquals("github-inbox:o/r/pull/7", page.items.single().id)
        assertEquals("o/r", page.items.single().author)
    }

    @Test fun `typed search is scoped to involves me`() {
        runBlocking { source().search(SearchQuery(term = "flaky test")) }
        val q = URLDecoder.decode(paths.single().substringAfter("q=").substringBefore('&'), "UTF-8")
        assertTrue(q, q.startsWith("involves:@me flaky test"))
    }

    @Test fun `applyFilters composes the view qualifier and keeps closed out by default`() {
        val state = FilterState(mapOf(GitHubInboxSource.FILTER_VIEW to FilterValue.StringVal("mentions")))
        val q = source().applyFilters(SearchQuery(term = "crash"), state).term
        assertEquals("crash mentions:@me is:open", q)
        val withClosed = state.with(GitHubInboxSource.FILTER_INCLUDE_CLOSED, FilterValue.BoolVal(true))
        assertEquals("mentions:@me", source().applyFilters(SearchQuery(), withClosed).term)
    }

    @Test fun `detail exposes overview and conversation chapters`() {
        val detail = (runBlocking { source().fictionDetail("github-inbox:o/r/pull/7") } as FictionResult.Success).value
        assertEquals(listOf("Overview", "Conversation"), detail.chapters.map { it.title })
        assertEquals("github-inbox:o/r/pull/7:overview", detail.chapters[0].id)
        assertEquals("Fix the flaky test", detail.summary.title)
    }

    @Test fun `overview narrates title, state, diff size, checks and description`() {
        val ch = runBlocking {
            source().chapter("github-inbox:o/r/pull/7", "github-inbox:o/r/pull/7:overview")
        } as FictionResult.Success
        val text = ch.value.plainBody
        assertTrue(text, text.contains("Fix the flaky test"))
        assertTrue(text, text.contains("Pull request 7 in o/r, opened by alice"))
        assertTrue(text, text.contains("Open, draft."))
        assertTrue(text, text.contains("3 files changed, 40 additions and 2 deletions"))
        assertTrue(text, text.contains("Checks: 1 passed, 1 failed (Build APK)."))
        assertTrue(text, text.contains("Retries the socket once."))
        assertFalse("HTML comments must not be narrated: $text", text.contains("template"))
    }

    @Test fun `conversation narrates comments and reviews in time order`() {
        val ch = runBlocking {
            source().chapter("github-inbox:o/r/pull/7", "github-inbox:o/r/pull/7:conversation")
        } as FictionResult.Success
        val text = ch.value.plainBody
        val bob = text.indexOf("bob commented")
        val carol = text.indexOf("carol approved these changes")
        val dave = text.indexOf("dave commented on src/Net.kt")
        assertTrue(text, bob >= 0 && carol > bob && dave > carol)
        assertTrue(text, text.contains("Looks right to me."))
        // An empty COMMENTED review is a container for inline comments — not narrated.
        assertFalse(text, text.contains("erin"))
    }

    @Test fun `malformed thread id is NotFound, not a crash`() {
        val result = runBlocking { source().fictionDetail("github-inbox:not-a-thread") }
        assertTrue("got $result", result is FictionResult.NotFound)
    }

    @Test fun `a Release notification is listed as a release thread`() {
        val page = (runBlocking { source().popular(1) } as FictionResult.Success).value
        val release = page.items.single { it.id == "github-inbox:o/r/releases/42" }
        assertTrue(release.tags.toString(), "Release" in release.tags)
        assertEquals("v2.0: Big one", release.title)
    }

    @Test fun `a release thread has one Release notes chapter narrating the notes`() {
        val detail = (runBlocking { source().fictionDetail("github-inbox:o/r/releases/42") }
            as FictionResult.Success).value
        assertEquals(listOf("Release notes"), detail.chapters.map { it.title })
        val chapter = (runBlocking {
            source().chapter("github-inbox:o/r/releases/42", detail.chapters.single().id)
        } as FictionResult.Success).value
        val text = chapter.plainBody
        assertTrue(text, text.contains("v2.0"))
        assertTrue(text, text.contains("dana"))
        assertTrue(text, text.contains("Faster startup"))
        assertTrue("fetched the release, not an issue: $paths", paths.any { it.startsWith("/repos/o/r/releases/42") })
        assertTrue("never fetched an issue: $paths", paths.none { it.contains("/issues/") })
    }

    @Test fun `lastPage jumps to the newest comments`() {
        assertEquals(1, GitHubInboxSource.lastPage(0))
        assertEquals(1, GitHubInboxSource.lastPage(100))
        assertEquals(2, GitHubInboxSource.lastPage(101))
    }

    private companion object {
        const val NOTIFICATIONS = """[{"id":"1","unread":true,"reason":"subscribed",
            "subject":{"title":"v2.0: Big one","url":"https://api.github.com/repos/o/r/releases/42","type":"Release"},
            "repository":{"full_name":"o/r"}}]"""
        const val RELEASE = """{"id":42,"name":"v2.0: Big one","tag_name":"v2.0",
            "html_url":"https://github.com/o/r/releases/tag/v2.0","author":{"login":"dana"},
            "published_at":"2026-10-01T12:00:00Z","body":"## Highlights\n\n- Faster startup\n- Fewer bugs"}"""
        const val SEARCH = """{"total_count":1,"items":[{"number":7,"title":"Fix the flaky test",
            "user":{"login":"alice"},"state":"open","repository_url":"https://api.github.com/repos/o/r",
            "html_url":"https://github.com/o/r/pull/7","comments":1,"pull_request":{"url":"x"}}]}"""

        const val ISSUE = """{"number":7,"title":"Fix the flaky test","body":"<!-- template -->Retries the socket once.",
            "user":{"login":"alice"},"state":"open","repository_url":"https://api.github.com/repos/o/r",
            "comments":1,"updated_at":"2026-09-26T12:00:00Z","draft":true,"pull_request":{"url":"x"}}"""

        const val PULL = """{"number":7,"draft":true,"merged":false,"additions":40,"deletions":2,
            "changed_files":3,"review_comments":1,"head":{"ref":"fix/flaky","sha":"abc123"},"base":{"ref":"main","sha":"def"}}"""

        const val CHECKS = """{"total_count":2,"check_runs":[
            {"name":"Build APK","status":"completed","conclusion":"failure"},
            {"name":"Lint","status":"completed","conclusion":"success"}]}"""

        const val ISSUE_COMMENTS = """[{"user":{"login":"bob"},"body":"Is this still flaky?","created_at":"2026-09-25T09:00:00Z"}]"""

        const val REVIEWS = """[
            {"user":{"login":"carol"},"body":"Looks right to me.","state":"APPROVED","submitted_at":"2026-09-25T10:00:00Z"},
            {"user":{"login":"erin"},"body":"","state":"COMMENTED","submitted_at":"2026-09-25T10:30:00Z"}]"""

        const val REVIEW_COMMENTS = """[{"user":{"login":"dave"},"body":"Nit: name this retryOnce.",
            "path":"src/Net.kt","created_at":"2026-09-25T11:00:00Z"}]"""
    }
}
