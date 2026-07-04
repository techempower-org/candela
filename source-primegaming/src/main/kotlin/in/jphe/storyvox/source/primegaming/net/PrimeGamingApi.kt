package `in`.jphe.storyvox.source.primegaming.net

import dagger.Lazy
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.primegaming.config.PrimeGamingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #1494 — HTTP client for the LootScraper Amazon-Prime Atom feed.
 *
 * There is exactly one upstream request: GET the configured feed URL. Everything
 * the source exposes (the collection, the claim list, each claim's narration) is
 * derived from that one document, so this client:
 *
 *  - **pins the blocking OkHttp call to `Dispatchers.IO`** (#585 — the contract
 *    kit asserts it),
 *  - **maps every non-2xx to a typed [FictionResult] failure** (never throws an
 *    HTTP error up to the source layer — requirement: graceful feed-down/moved),
 *  - **caches the parsed feed in-memory with a short TTL** so tapping through
 *    the claim list doesn't re-download the whole feed per chapter (the #1489
 *    lesson: `chapter()` must not re-fetch), and
 *  - **honours conditional-GET etiquette** — it records `ETag`/`Last-Modified`
 *    and replays them as `If-None-Match`/`If-Modified-Since`, treating a 304 as
 *    "reuse the cached parse" (the feed supports 304, verified on #1494).
 *
 * The feed URL is read from [PrimeGamingConfig] behind a `dagger.Lazy` so the
 * FictionSource → settings-config edge can't form the #1309 Dagger init cycle.
 * `open` so unit tests can substitute behaviour; the contract test injects a
 * fake config pointed at its MockWebServer.
 */
internal open class PrimeGamingApi(
    private val client: OkHttpClient,
    private val config: Lazy<PrimeGamingConfig>,
) {
    /** A parsed feed plus the revision token to hand the cheap-poll worker. */
    internal data class FeedFetch(val feed: PrimeGamingFeed, val revision: String?)

    private data class Cached(
        val fetch: FeedFetch,
        val lastModified: String?,
        val etag: String?,
        val atNanos: Long,
    )

    @Volatile
    private var cache: Cached? = null

    /**
     * Fetch (or return the cached) parsed feed. IO-pinned; typed failures only.
     *
     * @param forceRefresh skip the TTL cache and re-hit the network (still
     *  sends conditional headers, so an unchanged feed costs a cheap 304).
     */
    open suspend fun feed(forceRefresh: Boolean = false): FictionResult<FeedFetch> =
        withContext(Dispatchers.IO) {
            val snapshot = cache
            if (!forceRefresh && snapshot != null &&
                System.nanoTime() - snapshot.atNanos < CACHE_TTL_NANOS
            ) {
                return@withContext FictionResult.Success(snapshot.fetch)
            }

            val url = config.get().feedUrl()
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/atom+xml, application/xml, text/xml")
                    .get()
                // Conditional-GET etiquette — the feed answers 304 when unchanged.
                snapshot?.etag?.let { builder.header("If-None-Match", it) }
                snapshot?.lastModified?.let { builder.header("If-Modified-Since", it) }

                client.newCall(builder.build()).execute().use { resp ->
                    when {
                        resp.code == 304 && snapshot != null -> {
                            // Upstream unchanged — reuse the parse, refresh the TTL.
                            cache = snapshot.copy(atNanos = System.nanoTime())
                            FictionResult.Success(snapshot.fetch)
                        }

                        resp.code == 404 ->
                            FictionResult.NotFound("Prime Gaming feed not found at $url")

                        resp.code == 401 ->
                            FictionResult.AuthRequired("HTTP 401 from $url")

                        resp.code == 403 -> {
                            // A Cloudflare interstitial arrives as 403 + challenge
                            // HTML — the CF sniff MUST precede the auth mapping, or
                            // a CF-gated 403 misreports as "sign in required".
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "Prime Gaming feed returned a Cloudflare challenge — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired("HTTP 403 from $url")
                            }
                        }

                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = parseRetryAfter(resp.header("Retry-After")),
                            message = "Prime Gaming feed rate limited (HTTP 429)",
                        )

                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from $url",
                            IOException("HTTP ${resp.code}"),
                        )

                        else -> {
                            val text = resp.body?.string()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty body",
                                    IOException("empty body"),
                                )
                            val parsed = PrimeGamingFeed.parse(text)
                            val lastModified = resp.header("Last-Modified")
                            val etag = resp.header("ETag")
                            val fetch = FeedFetch(
                                feed = parsed,
                                revision = etag ?: lastModified ?: parsed.updated,
                            )
                            cache = Cached(fetch, lastModified, etag, System.nanoTime())
                            FictionResult.Success(fetch)
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            }
        }

    /**
     * #1443-family heuristic: does this body look like a Cloudflare challenge
     * interstitial rather than the Atom feed? Keep ahead of the 401/403 mapping.
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    /** `Retry-After` is either delta-seconds or an HTTP date; we honour the
     *  common integer-seconds form and ignore the date form (null → unknown). */
    private fun parseRetryAfter(header: String?): Duration? =
        header?.trim()?.toIntOrNull()?.takeIf { it >= 0 }?.seconds

    companion object {
        /** Long enough that browsing the claim list is one fetch; short enough
         *  that a monthly Prime refresh shows up promptly on the next open. */
        private val CACHE_TTL_NANOS: Long = 5.minutes.inWholeNanoseconds
    }
}
