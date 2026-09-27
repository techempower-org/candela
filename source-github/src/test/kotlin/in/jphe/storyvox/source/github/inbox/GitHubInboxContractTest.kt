package `in`.jphe.storyvox.source.github.inbox

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.github.render.MarkdownChapterRenderer
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * #1470 — the GitHub inbox narrator against the shared contract kit:
 * `popular()` → `GET /notifications` must stay IO-pinned, map 401 →
 * AuthRequired, 429 → RateLimited, a Cloudflare 403 → NetworkError (not
 * AuthRequired), and return `github-inbox:`-prefixed ids.
 */
class GitHubInboxContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        val api = object : GitHubInboxApi(client) {
            override val baseUrl: String get() = host
        }
        return GitHubInboxSource(api, FakeGitHubAuth(signedIn = true), MarkdownChapterRenderer())
    }

    override fun happyListBody(): String = """
        [
          {
            "id": "1",
            "unread": true,
            "reason": "review_requested",
            "updated_at": "2026-09-26T10:00:00Z",
            "subject": {
              "title": "Add the inbox narrator",
              "url": "https://api.github.com/repos/techempower-org/candela/pulls/1470",
              "type": "PullRequest"
            },
            "repository": { "full_name": "techempower-org/candela" }
          },
          {
            "id": "2",
            "unread": false,
            "reason": "subscribed",
            "subject": { "title": "v1.15.0", "url": null, "type": "Release" },
            "repository": { "full_name": "techempower-org/candela" }
          }
        ]
    """.trimIndent()

    override fun listPathFragment(): String = "/notifications"
}
