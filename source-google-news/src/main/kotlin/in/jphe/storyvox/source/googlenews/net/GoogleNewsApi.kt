package `in`.jphe.storyvox.source.googlenews.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Issue #1238 — thin OkHttp wrapper that fetches a Google News RSS feed
 * URL and returns its raw XML body. Parsing is [GoogleNewsParser]'s job;
 * routing/feed-URL construction is [GoogleNewsSections]'.
 */
internal open class GoogleNewsApi(private val client: OkHttpClient) {

    /**
     * #1491 — base origin for feed fetches. `open` so JVM unit tests can point
     * the network path at a MockWebServer without restructuring
     * [GoogleNewsSections]' URL construction (mirrors `StandardEbooksApi.baseUrl`
     * / `PlosApi.base`). Production keeps Google News; [rebaseFeedUrl] swaps only
     * the origin so the feed URL's path + query (section / topic / search
     * routing) is preserved verbatim.
     */
    internal open val baseUrl: String get() = DEFAULT_BASE

    /**
     * Fetch a feed [url] and return the raw XML on success.
     *
     * #585 — pinned to [Dispatchers.IO]: an accidental main-thread call
     * site would otherwise throw `NetworkOnMainThreadException`, which is
     * runtime-only and never caught by a JVM unit test. IO failures come
     * back as [FictionResult.NetworkError] with the cause attached.
     *
     * #1491 — status mapping now matches the other HTTP sources (and the
     * `FictionSourceContractTest` contract): 401/403 -> [FictionResult.AuthRequired]
     * (unless the body is a Cloudflare challenge, which stays
     * [FictionResult.Cloudflare]), 429 -> [FictionResult.RateLimited], other
     * non-2xx -> [FictionResult.NetworkError]. Previously every non-success
     * collapsed to NetworkError, so an auth gate or a rate-limit was
     * indistinguishable from a transient network blip.
     */
    suspend fun fetchFeed(url: String): FictionResult<String> = withContext(Dispatchers.IO) {
        val target = rebaseFeedUrl(url)
        try {
            client.newCall(Request.Builder().url(target).build()).execute().use { resp ->
                when {
                    // A Cloudflare 403 is not a login gate — peek the body so it
                    // surfaces as Cloudflare, not AuthRequired (a sign-in prompt
                    // can't clear a CF challenge).
                    resp.code == 401 || resp.code == 403 -> {
                        val body = resp.body?.string().orEmpty()
                        if (looksLikeCfChallenge(body)) {
                            FictionResult.Cloudflare(
                                challengeUrl = target,
                                message = "Google News returned a Cloudflare challenge",
                            )
                        } else {
                            FictionResult.AuthRequired("Google News HTTP ${resp.code}")
                        }
                    }
                    resp.code == 429 -> FictionResult.RateLimited(
                        retryAfter = null,
                        message = "Google News rate limited (HTTP 429)",
                    )
                    !resp.isSuccessful ->
                        FictionResult.NetworkError("Google News HTTP ${resp.code}")
                    else -> {
                        val body = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError("Empty Google News response")
                        if (looksLikeCfChallenge(body)) {
                            return@withContext FictionResult.Cloudflare(
                                challengeUrl = target,
                                message = "Google News returned a Cloudflare challenge",
                            )
                        }
                        FictionResult.Success(body)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError("Google News fetch failed", e)
        }
    }

    /**
     * #1491 — swap the production origin for [baseUrl], keeping the path + query
     * intact. In production [baseUrl] is [DEFAULT_BASE], so this is the identity;
     * a test overriding [baseUrl] redirects the same feed path at its
     * MockWebServer. Any URL that doesn't start with [DEFAULT_BASE] is passed
     * through unchanged (defensive — all feed URLs come from [GoogleNewsSections],
     * which always builds off Google News).
     */
    private fun rebaseFeedUrl(url: String): String {
        val base = baseUrl.trimEnd('/')
        if (base == DEFAULT_BASE) return url
        val path = url.removePrefix(DEFAULT_BASE)
        return if (path === url) url else base + path
    }

    /**
     * Inline Cloudflare challenge detection (#1446).
     *
     * `cf-mitigated` is an unambiguous signal (CF sets it only on blocked
     * responses). The `<title>Just a moment</title>` pattern identifies
     * the JS challenge interstitial — combined with a body-length cap to
     * avoid false-positives on legitimate pages that happen to contain
     * that substring. `/cdn-cgi/challenge-platform/` was removed because
     * Turnstile injects it on every CF-fronted page.
     *
     * Shared utility deferred to #1438.
     */
    private fun looksLikeCfChallenge(body: String): Boolean {
        if (body.contains("cf-mitigated")) return true
        val hasChallengeTitle = "<title>" in body &&
            body.substringAfter("<title>").substringBefore("</title>")
                .contains("Just a moment", ignoreCase = true)
        return hasChallengeTitle && body.length < 20_000
    }

    companion object {
        /** Production origin all [GoogleNewsSections] feed URLs are built from. */
        const val DEFAULT_BASE: String = "https://news.google.com"
    }
}
