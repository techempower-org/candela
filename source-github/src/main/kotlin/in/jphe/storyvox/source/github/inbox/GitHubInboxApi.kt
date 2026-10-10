package `in`.jphe.storyvox.source.github.inbox

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.github.di.GitHubHttp
import `in`.jphe.storyvox.source.github.net.GitHubApi
import `in`.jphe.storyvox.source.github.net.GitHubJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #1470 — authed REST client for the GitHub inbox narrator.
 *
 * Rides the same [GitHubHttp] OkHttp client as [GitHubApi], so the
 * signed-in user's OAuth bearer is attached by `GitHubAuthInterceptor`
 * (host-pinned to api.github.com) — no second token store.
 *
 * Unlike [GitHubApi] this maps straight to the cross-source
 * [FictionResult] contract (FictionSourceContractTest): 401 → AuthRequired,
 * 429 / 403-with-exhausted-quota → RateLimited, a Cloudflare 403 →
 * NetworkError, and any other 403 → AuthRequired (the token lacks a scope —
 * `/notifications` needs `notifications`, which tokens granted before #1470
 * don't carry).
 */
@Singleton
internal open class GitHubInboxApi @Inject constructor(
    @GitHubHttp private val client: OkHttpClient,
) {
    /** Test seam — overridden to point at a MockWebServer. */
    internal open val baseUrl: String get() = GitHubApi.BASE_URL

    /** `GET /notifications` — unread threads for the signed-in user. Needs the `notifications` scope. */
    suspend fun notifications(page: Int, perPage: Int = PER_PAGE): FictionResult<List<GhNotification>> =
        get("/notifications?page=$page&per_page=$perPage")

    /** `GET /search/issues` — [query] carries GitHub qualifiers (`review-requested:@me is:open`). */
    suspend fun searchIssues(query: String, page: Int, perPage: Int = PER_PAGE): FictionResult<GhIssueSearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        return get("/search/issues?q=$q&sort=updated&order=desc&page=$page&per_page=$perPage")
    }

    suspend fun issue(owner: String, repo: String, number: Int): FictionResult<GhIssue> =
        get("/repos/$owner/$repo/issues/$number")

    suspend fun pull(owner: String, repo: String, number: Int): FictionResult<GhPull> =
        get("/repos/$owner/$repo/pulls/$number")

    /** A release, for Release notifications (#1841). */
    suspend fun release(owner: String, repo: String, id: Int): FictionResult<GhRelease> =
        get("/repos/$owner/$repo/releases/$id")

    /** Issue-timeline comments, one page of up to 100 (oldest first). */
    suspend fun issueComments(owner: String, repo: String, number: Int, page: Int): FictionResult<List<GhComment>> =
        get("/repos/$owner/$repo/issues/$number/comments?per_page=$COMMENT_PAGE&page=$page")

    /** Inline review comments on a PR's diff, one page of up to 100 (oldest first). */
    suspend fun reviewComments(owner: String, repo: String, number: Int, page: Int): FictionResult<List<GhComment>> =
        get("/repos/$owner/$repo/pulls/$number/comments?per_page=$COMMENT_PAGE&page=$page")

    suspend fun reviews(owner: String, repo: String, number: Int): FictionResult<List<GhReview>> =
        get("/repos/$owner/$repo/pulls/$number/reviews?per_page=$COMMENT_PAGE")

    suspend fun checkRuns(owner: String, repo: String, sha: String): FictionResult<GhCheckRunsResponse> =
        get("/repos/$owner/$repo/commits/$sha/check-runs?per_page=$COMMENT_PAGE")

    /**
     * IO-pinned GET (#585). Never throws for HTTP, network or parse
     * failures — every one comes back as a typed [FictionResult.Failure].
     */
    private suspend inline fun <reified T> get(path: String): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + path
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", GitHubApi.API_VERSION)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 -> FictionResult.AuthRequired(
                            "GitHub session expired — sign in again in Settings",
                        )
                        resp.code == 403 -> {
                            // Order matters: a Cloudflare interstitial and an
                            // exhausted quota are both 403s but neither is a
                            // login gate, so they're sniffed before the auth arm.
                            val body = resp.body?.string().orEmpty()
                            when {
                                looksLikeCfChallenge(body) -> FictionResult.NetworkError(
                                    "GitHub returned a challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                                resp.header("X-RateLimit-Remaining") == "0" -> FictionResult.RateLimited(
                                    retryAfter = retryAfterSeconds(
                                        resp.header("Retry-After"),
                                        resp.header("X-RateLimit-Reset"),
                                    )?.seconds,
                                    message = "GitHub rate limit reached",
                                )
                                else -> FictionResult.AuthRequired(
                                    "GitHub denied access — sign in again to grant notification access",
                                )
                            }
                        }
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = retryAfterSeconds(resp.header("Retry-After"), null)?.seconds,
                            message = "GitHub rate limit reached (HTTP 429)",
                        )
                        resp.code == 404 -> FictionResult.NotFound("GitHub: nothing at $path")
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from GitHub",
                            IOException("HTTP ${resp.code}"),
                        )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty body",
                                    IOException("empty body"),
                                )
                            FictionResult.Success(GitHubJson.decodeFromString<T>(text))
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "GitHub request failed", e)
            } catch (e: SerializationException) {
                FictionResult.NetworkError("GitHub returned an unexpected response shape", e)
            } catch (e: IllegalArgumentException) {
                // Malformed URL (e.g. an owner/repo with illegal characters)
                // or a decode failure kotlinx surfaces as IAE.
                FictionResult.NetworkError("GitHub request could not be built", e)
            }
        }

    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("/cdn-cgi/challenge") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val PER_PAGE: Int = 30
        const val COMMENT_PAGE: Int = 100

        /** Seconds until retry from `Retry-After`, else from the epoch in `X-RateLimit-Reset`. */
        internal fun retryAfterSeconds(retryAfter: String?, resetEpoch: String?): Long? =
            retryAfter?.toLongOrNull()
                ?: resetEpoch?.toLongOrNull()
                    ?.let { it - System.currentTimeMillis() / 1000 }
                    ?.coerceAtLeast(0)
    }
}
