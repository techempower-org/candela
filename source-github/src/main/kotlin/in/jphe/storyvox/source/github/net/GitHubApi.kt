package `in`.jphe.storyvox.source.github.net

import `in`.jphe.storyvox.source.github.di.GitHubHttp
import `in`.jphe.storyvox.source.github.model.GhCommitRef
import `in`.jphe.storyvox.source.github.model.GhCompareResponse
import `in`.jphe.storyvox.source.github.model.GhGist
import `in`.jphe.storyvox.source.github.model.GhSearchResponse
import `in`.jphe.storyvox.source.github.model.GhContent
import `in`.jphe.storyvox.source.github.model.GhRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.decodeFromStream
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Outcome of a GitHub REST call. Mirrors core-data's `FictionResult`
 * variants but stays internal to the source module so we can shape the
 * GitHub-specific error space (rate-limit headers, 404 vs 403, etc.)
 * before mapping to the cross-source contract.
 *
 * 60 req/hr unauthenticated rate limit is plenty with caching at the
 * repository layer (spec: GitHub source design, line 50). Auth/PAT
 * support is deferred — when it lands the mapping stays the same, just
 * pass an Authorization header on the way in.
 */
internal sealed class GitHubApiResult<out T> {
    data class Success<T>(val value: T, val etag: String?) : GitHubApiResult<T>()
    data class NotFound(val message: String) : GitHubApiResult<Nothing>()
    data class RateLimited(val retryAfterSeconds: Long?) : GitHubApiResult<Nothing>()
    data class HttpError(val code: Int, val message: String) : GitHubApiResult<Nothing>()
    data class NetworkError(val cause: Throwable) : GitHubApiResult<Nothing>()
    data class ParseError(val cause: Throwable) : GitHubApiResult<Nothing>()
}

/**
 * Thin client over the public GitHub v3 REST API. Endpoints used by
 * the source layer:
 *  - `getRepo` — existence check + repo metadata (default branch,
 *    description, topics, archived status).
 *  - `getContent` — single-file fetch for `book.toml` /
 *    `storyvox.json` manifests. Files come back base64-encoded.
 *  - `compareCommits` — base...head SHA polling for new chapters.
 *
 * All calls are `suspend`, dispatched to [Dispatchers.IO], and never
 * throw on HTTP/network errors — they return a [GitHubApiResult]
 * variant the caller can branch on. Programmer errors (deserialization
 * faults from genuinely malformed JSON) come back as `ParseError`.
 */
@Singleton
internal open class GitHubApi @Inject constructor(
    @GitHubHttp private val httpClient: OkHttpClient,
) {
    open suspend fun getRepo(owner: String, repo: String): GitHubApiResult<GhRepo> =
        get("$BASE_URL/repos/${owner.lowercase()}/${repo.lowercase()}")

    open suspend fun getContent(
        owner: String,
        repo: String,
        path: String,
        ref: String? = null,
    ): GitHubApiResult<GhContent> {
        val refParam = if (ref != null) "?ref=$ref" else ""
        val cleanPath = path.trimStart('/')
        return get("$BASE_URL/repos/${owner.lowercase()}/${repo.lowercase()}/contents/$cleanPath$refParam")
    }

    /**
     * Directory listing variant of [getContent]. The same `/contents`
     * endpoint returns a JSON array when the path is a directory. Used
     * by the bare-repo fallback to enumerate `chapters/` or `src/` for
     * numbered .md files when no `SUMMARY.md` is present.
     */
    open suspend fun getContents(
        owner: String,
        repo: String,
        path: String,
        ref: String? = null,
    ): GitHubApiResult<List<GhContent>> {
        val refParam = if (ref != null) "?ref=$ref" else ""
        val cleanPath = path.trimStart('/')
        return get("$BASE_URL/repos/${owner.lowercase()}/${repo.lowercase()}/contents/$cleanPath$refParam")
    }

    open suspend fun compareCommits(
        owner: String,
        repo: String,
        base: String,
        head: String,
    ): GitHubApiResult<GhCompareResponse> =
        get("$BASE_URL/repos/${owner.lowercase()}/${repo.lowercase()}/compare/$base...$head")

    /**
     * `GET /repos/{owner}/{repo}/commits?sha={ref}&per_page=1` — returns
     * the head commit on [ref] (or the repo's default branch if [ref]
     * is null). Used by `latestRevisionToken` in [GitHubSource] for the
     * cheap-poll path: a 5-line JSON response (~1KB) the worker compares
     * against the stored token.
     */
    open suspend fun getHeadCommit(
        owner: String,
        repo: String,
        ref: String? = null,
    ): GitHubApiResult<List<GhCommitRef>> {
        val refParam = if (ref != null) "&sha=$ref" else ""
        return get(
            "$BASE_URL/repos/${owner.lowercase()}/${repo.lowercase()}/commits?per_page=1$refParam",
        )
    }

    /**
     * `GET /search/repositories?q=...&page=...&per_page=...`. The
     * search endpoint has its own 30 req/min unauthenticated rate
     * limit (separate from the core 60/hr limit), so a 300 ms
     * debounced typed-search UX fits well under the cap.
     *
     * [query] should already include any qualifier prefixes
     * (`topic:fiction`, `language:en`, etc.) — this method just URL-
     * encodes and forwards. Caller is responsible for composing the
     * fiction-targeting topics into [query].
     */
    open suspend fun searchRepositories(
        query: String,
        page: Int = 1,
        perPage: Int = 20,
    ): GitHubApiResult<GhSearchResponse> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return get(
            "$BASE_URL/search/repositories?q=$encoded&page=$page&per_page=$perPage",
        )
    }

    /**
     * `GET /user/repos?affiliation=owner,collaborator&sort=updated&page=N&per_page=P`.
     *
     * Issue #200. Returns repos the authenticated user owns or has been
     * granted collaborator access to, ordered by most-recently-pushed.
     * The auth bearer is attached transparently by [`in`.jphe.storyvox.
     * source.github.auth.GitHubAuthInterceptor]; an unauthenticated
     * caller gets a 401 mapped to `HttpError(401, ...)`.
     *
     * Pagination is by `Link: rel=next` header on GitHub's side; we
     * don't parse it — the caller infers `hasNext` from
     * `items.size >= perPage` (a full page strongly implies another
     * exists; a short page is always the last one).
     */
    open suspend fun myRepos(
        page: Int = 1,
        perPage: Int = 20,
    ): GitHubApiResult<List<GhRepo>> = get(
        "$BASE_URL/user/repos?affiliation=owner,collaborator&sort=updated&page=$page&per_page=$perPage",
    )

    /**
     * `GET /user/starred?sort=updated`. Auth-only — the
     * [GitHubAuthInterceptor] attaches the bearer token; an unauthenticated
     * request comes back as 401 → `HttpError(401, ...)` from
     * [mapResponse]. Sorted by recent star activity so the suggestions
     * row shows what the user has been bookmarking lately, not their
     * oldest stars.
     *
     * Issue #201. Same `hasNext` pagination convention as [myRepos] —
     * the caller infers from raw upstream page size, not Link headers.
     */
    open suspend fun starredRepos(
        page: Int = 1,
        perPage: Int = 20,
    ): GitHubApiResult<List<GhRepo>> = get(
        "$BASE_URL/user/starred?sort=updated&page=$page&per_page=$perPage",
    )

    /**
     * `GET /users/{user}/gists` — public gists for [user]. The listing
     * shape returns metadata + a stub `files` map without `content`;
     * use [getGist] to fetch a single gist with bodies inline.
     *
     * https://docs.github.com/en/rest/gists/gists#list-gists-for-a-user
     */
    open suspend fun userGists(
        user: String,
        page: Int = 1,
        perPage: Int = 30,
    ): GitHubApiResult<List<GhGist>> {
        val safeUser = java.net.URLEncoder.encode(user.trim(), "UTF-8")
        return get("$BASE_URL/users/$safeUser/gists?page=$page&per_page=$perPage")
    }

    /**
     * `GET /gists` — gists for the authenticated user. Includes
     * private/secret gists in the response when the bearer token has
     * the `gist` scope. Falls back to public-only when the token is
     * absent or scope-deficient.
     *
     * https://docs.github.com/en/rest/gists/gists#list-gists-for-the-authenticated-user
     */
    open suspend fun authenticatedUserGists(
        page: Int = 1,
        perPage: Int = 30,
    ): GitHubApiResult<List<GhGist>> =
        get("$BASE_URL/gists?page=$page&per_page=$perPage")

    /**
     * `GET /gists/{gist_id}` — fetch a single gist with full inline
     * file bodies. The listing endpoints omit `files[*].content`; this
     * is the canonical fetch path for rendering.
     *
     * https://docs.github.com/en/rest/gists/gists#get-a-gist
     */
    open suspend fun getGist(gistId: String): GitHubApiResult<GhGist> {
        val safeId = java.net.URLEncoder.encode(gistId.trim(), "UTF-8")
        return get("$BASE_URL/gists/$safeId")
    }

    /**
     * `GET /user/repos` with exhaustive pagination. Fetches ALL repos
     * for the authenticated user (owner + collaborator), not just one
     * page. Used by the auto-import flow (#763) to discover book-shaped
     * repos across the user's entire GitHub account.
     *
     * Returns the accumulated list across all pages. Stops when a page
     * comes back shorter than [perPage] (no more pages). Caps at
     * [maxPages] to bound runtime for users with thousands of repos.
     */
    open suspend fun allMyRepos(
        perPage: Int = 100,
        maxPages: Int = 50,
    ): GitHubApiResult<List<GhRepo>> {
        val all = mutableListOf<GhRepo>()
        for (page in 1..maxPages) {
            when (val r = myRepos(page = page, perPage = perPage)) {
                is GitHubApiResult.Success -> {
                    all.addAll(r.value)
                    if (r.value.size < perPage) break
                }
                is GitHubApiResult.NotFound -> break
                is GitHubApiResult.RateLimited -> return r
                is GitHubApiResult.HttpError -> return r
                is GitHubApiResult.NetworkError -> return r
                is GitHubApiResult.ParseError -> return r
            }
        }
        return GitHubApiResult.Success(all, etag = null)
    }

    private suspend inline fun <reified T> get(url: String): GitHubApiResult<T> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .build()
            val response = try {
                httpClient.newCall(req).await()
            } catch (e: IOException) {
                return@withContext GitHubApiResult.NetworkError(e)
            }
            response.use { r -> mapResponse<T>(r) }
        }

    @OptIn(ExperimentalSerializationApi::class)
    private inline fun <reified T> mapResponse(r: Response): GitHubApiResult<T> {
        if (r.isSuccessful) {
            val body = r.body ?: return GitHubApiResult.NetworkError(
                IOException("Empty response body"),
            )
            return try {
                val parsed = GitHubJson.decodeFromStream<T>(body.byteStream())
                GitHubApiResult.Success(parsed, etag = r.header("ETag"))
            } catch (e: SerializationException) {
                GitHubApiResult.ParseError(e)
            }
        }
        return when (r.code) {
            404 -> GitHubApiResult.NotFound(r.message.ifBlank { "Not found" })
            403, 429 -> {
                // Both signal rate-limit on GitHub: 403 with the
                // X-RateLimit-Remaining header at zero is the canonical
                // "you're throttled" response on the public API.
                val resetEpoch = r.header("X-RateLimit-Reset")?.toLongOrNull()
                val retryAfter = r.header("Retry-After")?.toLongOrNull()
                    ?: resetEpoch?.let { it - System.currentTimeMillis() / 1000 }
                GitHubApiResult.RateLimited(retryAfter)
            }
            else -> GitHubApiResult.HttpError(r.code, r.message)
        }
    }

    companion object {
        const val BASE_URL: String = "https://api.github.com"
        const val API_VERSION: String = "2022-11-28"
        // Generic UA — GitHub requires *something* in User-Agent for
        // unauthenticated REST calls. App version travels in BuildConfig
        // when we need it; for now identifying as the project is enough.
        // #1204 — UA applied via the shared @UserAgentHeader interceptor (UserAgent.kt).
    }
}

/** Suspending bridge over OkHttp's enqueue → callback API. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            if (cont.isCancelled) return
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation {
        runCatching { cancel() }
    }
}
