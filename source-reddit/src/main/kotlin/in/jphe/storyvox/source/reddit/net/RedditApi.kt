package `in`.jphe.storyvox.source.reddit.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.reddit.config.RedditConfig
import `in`.jphe.storyvox.source.reddit.config.RedditConfigState
import `in`.jphe.storyvox.source.reddit.config.RedditDefaults
import `in`.jphe.storyvox.source.reddit.config.RedditPostSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Base64
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #1492 — reddit OAuth2 JSON API client (installed-app, userless BYOK).
 *
 * ## Token lifecycle
 *
 * Reddit's userless grant mints a **read-only bearer valid ~1 hour**.
 * We POST to `<tokenBaseUrl>/api/v1/access_token` with HTTP Basic auth
 * (`client_id:` — empty password, per reddit's installed-app spec) and
 * `grant_type=installed_client&device_id=<uuid>`. The bearer is cached
 * in-memory with an expiry (renewed [RedditDefaults.TOKEN_EXPIRY_MARGIN_SECONDS]
 * early); only the client id + device id are persisted. A [Mutex] serialises
 * concurrent mints so a burst of first calls doesn't hammer the token
 * endpoint.
 *
 * ## Failure contract
 *
 * Every network call — token mint AND API GET — funnels through
 * [execute], which maps status codes to typed [FictionResult] failures
 * (401/403 → AuthRequired, CF-403 → NetworkError, 404 → NotFound, 429 →
 * RateLimited, 5xx/IO → NetworkError) and never throws for an HTTP
 * error (#585 IO-pin + the CONTRIBUTING decision table). The CF sniff
 * runs ahead of the auth mapping so a challenge page never misreports
 * as "sign in required" — though reddit fronts with Fastly, not
 * Cloudflare, so this is defence-in-depth the contract kit still asserts.
 *
 * ## Honest User-Agent
 *
 * The shared descriptive `@UserAgentHeader` interceptor (#1204) is
 * installed on the client by the DI module — reddit *requires* a unique
 * descriptive UA and throttles generic ones aggressively. No browser-UA
 * spoofing.
 *
 * `open` (class + [baseUrls]/[nowMillis]) so unit tests can retarget
 * hosts at a MockWebServer and drive the token-expiry clock.
 */
internal open class RedditApi @Inject constructor(
    private val client: OkHttpClient,
    private val config: RedditConfig,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val tokenMutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenExpiryMillis: Long = 0L

    /** Test seam — wall clock for token-expiry checks. */
    internal open fun nowMillis(): Long = System.currentTimeMillis()

    // ─── endpoints ──────────────────────────────────────────────────────

    /** Trending subreddits → discovery list of subreddit-fictions. */
    suspend fun popularSubreddits(): FictionResult<RedditListingEnvelope> =
        authedGet("/subreddits/popular", mapOf("limit" to LIST_LIMIT)) { decodeListing(it) }

    /** Newest subreddits → the "latest updates" discovery list. */
    suspend fun newSubreddits(): FictionResult<RedditListingEnvelope> =
        authedGet("/subreddits/new", mapOf("limit" to LIST_LIMIT)) { decodeListing(it) }

    /** Free-form subreddit search. */
    suspend fun searchSubreddits(term: String): FictionResult<RedditListingEnvelope> =
        authedGet("/subreddits/search", mapOf("q" to term, "limit" to LIST_LIMIT)) { decodeListing(it) }

    /** Subreddit metadata (`/r/<sub>/about`) for a FictionSummary. */
    suspend fun subredditAbout(subreddit: String): FictionResult<RedditThingData> =
        authedGet("/r/$subreddit/about", emptyMap()) {
            json.decodeFromString<RedditThingEnvelope>(it).data
        }

    /** A subreddit's posts by [sort] → chapter list. */
    suspend fun subredditPosts(
        subreddit: String,
        sort: RedditPostSort,
    ): FictionResult<RedditListingEnvelope> {
        val query = buildMap {
            put("limit", POST_LIMIT)
            put("raw_json", "1")
            // `top` needs a time window or reddit defaults to all-time,
            // which buries anything current. A week reads as "recent best".
            if (sort == RedditPostSort.TOP) put("t", "week")
        }
        return authedGet("/r/$subreddit/${sort.wire}", query) { decodeListing(it) }
    }

    /**
     * A post plus its top-level comments (`/r/<sub>/comments/<id>`).
     * Reddit returns a two-element array: `[postListing, commentListing]`.
     * [commentLimit] 0 skips the comment fetch cost entirely (still one
     * request, but we ask reddit for the minimum).
     */
    suspend fun postWithComments(
        subreddit: String,
        postId: String,
        commentLimit: Int,
    ): FictionResult<RedditPostBundle> {
        val query = mapOf(
            "raw_json" to "1",
            "sort" to "top",
            "depth" to "1",
            "limit" to commentLimit.coerceAtLeast(1).toString(),
        )
        return authedGet("/r/$subreddit/comments/$postId", query) { body ->
            val listings = json.decodeFromString<List<RedditListingEnvelope>>(body)
            val post = listings.getOrNull(0)?.data?.children?.firstOrNull()?.data
            val comments = listings.getOrNull(1)?.data?.children.orEmpty().map { it.data }
            RedditPostBundle(post = post, comments = comments)
        }
    }

    // ─── token ──────────────────────────────────────────────────────────

    /**
     * Return a valid bearer token, minting one if the cache is empty or
     * stale. [FictionResult.AuthRequired] when no client id is configured.
     */
    private suspend fun bearer(state: RedditConfigState): FictionResult<String> {
        cachedToken?.let { if (nowMillis() < tokenExpiryMillis) return FictionResult.Success(it) }
        return tokenMutex.withLock {
            // Re-check inside the lock — a sibling call may have minted
            // while we waited.
            cachedToken?.let {
                if (nowMillis() < tokenExpiryMillis) return@withLock FictionResult.Success(it)
            }
            if (state.clientId.isBlank()) {
                return@withLock FictionResult.AuthRequired(
                    "Reddit client id not configured. Create an installed app at " +
                        "reddit.com/prefs/apps and paste its client id in Settings " +
                        "(see docs/reddit-setup.md).",
                )
            }
            mintToken(state)
        }
    }

    private suspend fun mintToken(state: RedditConfigState): FictionResult<String> {
        val creds = Base64.getEncoder()
            .encodeToString("${state.clientId}:".toByteArray(Charsets.UTF_8))
        val form = FormBody.Builder()
            .add("grant_type", RedditDefaults.INSTALLED_CLIENT_GRANT)
            .add("device_id", state.deviceId.ifBlank { RedditDefaults.DEFAULT_DEVICE_ID })
            .build()
        val req = Request.Builder()
            .url("${state.tokenBaseUrl.trimEnd('/')}/api/v1/access_token")
            .header("Authorization", "Basic $creds")
            .header("Accept", "application/json")
            .post(form)
            .build()
        return when (val raw = execute(req)) {
            is FictionResult.Success -> {
                val parsed = try {
                    json.decodeFromString<RedditTokenResponse>(raw.value)
                } catch (e: kotlinx.serialization.SerializationException) {
                    return FictionResult.NetworkError("Reddit token response was not valid JSON", e)
                }
                val token = parsed.accessToken?.takeIf { it.isNotBlank() }
                    ?: return FictionResult.NetworkError(
                        parsed.error?.let { "Reddit token error: $it" }
                            ?: "Reddit token response missing access_token",
                    )
                cachedToken = token
                val ttl = (parsed.expiresIn ?: 3600L) - RedditDefaults.TOKEN_EXPIRY_MARGIN_SECONDS
                tokenExpiryMillis = nowMillis() + ttl.coerceAtLeast(0L) * 1000L
                FictionResult.Success(token)
            }
            is FictionResult.Failure -> raw
        }
    }

    // ─── transport ────────────────────────────────────────────────────────

    private suspend fun <T> authedGet(
        path: String,
        query: Map<String, String>,
        parse: (String) -> T,
    ): FictionResult<T> {
        val state = config.current()
        val token = when (val b = bearer(state)) {
            is FictionResult.Success -> b.value
            is FictionResult.Failure -> return b
        }
        val builder = ("${state.apiBaseUrl.trimEnd('/')}$path").toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val req = Request.Builder()
            .url(builder.build())
            .header("Authorization", "bearer $token")
            .header("Accept", "application/json")
            .get()
            .build()
        return when (val raw = execute(req)) {
            is FictionResult.Success -> try {
                FictionResult.Success(parse(raw.value))
            } catch (e: kotlinx.serialization.SerializationException) {
                FictionResult.NetworkError("Reddit returned an unexpected response shape", e)
            }
            is FictionResult.Failure -> raw
        }
    }

    private fun decodeListing(body: String): RedditListingEnvelope =
        json.decodeFromString<RedditListingEnvelope>(body)

    /**
     * Execute a prebuilt request on [Dispatchers.IO] (#585) and map the
     * outcome to a typed [FictionResult]. The single choke-point every
     * call — token mint + every API GET — shares this mapping, so the
     * OAuth handshake obeys the same auth/rate/CF contract the API does.
     */
    private suspend fun execute(req: Request): FictionResult<String> =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(req).execute().use { resp ->
                    val url = req.url.toString()
                    when {
                        resp.code == 404 -> FictionResult.NotFound("Reddit: not found ($url)")
                        resp.code == 403 -> {
                            // CF sniff MUST precede the auth mapping (decision
                            // table): a challenge 403 is not a login gate.
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "Reddit returned a Cloudflare challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired(
                                    "Reddit rejected the request (HTTP 403) — check the client id / app type",
                                )
                            }
                        }
                        resp.code == 401 -> FictionResult.AuthRequired(
                            "Reddit rejected the OAuth credentials (HTTP 401)",
                        )
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = parseRetryAfter(resp.header("Retry-After")),
                            message = "Reddit rate-limited the request (HTTP 429). " +
                                "The free tier is ≈100 requests/minute per client id.",
                        )
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )
                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "Reddit returned an empty body",
                                    IOException("empty body"),
                                )
                            FictionResult.Success(text)
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            }
        }

    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        private val LIST_LIMIT = RedditDefaults.SUBREDDIT_LIST_LIMIT.toString()
        private val POST_LIMIT = RedditDefaults.POST_LIST_LIMIT.toString()

        /** Reddit's `Retry-After` is whole seconds (or an HTTP-date, rare). */
        internal fun parseRetryAfter(header: String?): Duration? =
            header?.trim()?.toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.seconds
    }
}

/** A post plus its fetched top-level comments (both may be absent). */
internal data class RedditPostBundle(
    val post: RedditThingData?,
    val comments: List<RedditThingData>,
)
