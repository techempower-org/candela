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
internal class GoogleNewsApi(private val client: OkHttpClient) {

    /**
     * Fetch a feed [url] and return the raw XML on success.
     *
     * #585 — pinned to [Dispatchers.IO]: an accidental main-thread call
     * site would otherwise throw `NetworkOnMainThreadException`, which is
     * runtime-only and never caught by a JVM unit test. IO failures come
     * back as [FictionResult.NetworkError] with the cause attached.
     */
    suspend fun fetchFeed(url: String): FictionResult<String> = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext FictionResult.NetworkError("Google News HTTP ${resp.code}")
                }
                val body = resp.body?.string()
                    ?: return@withContext FictionResult.NetworkError("Empty Google News response")
                if (looksLikeCfChallenge(body)) {
                    return@withContext FictionResult.Cloudflare(
                        challengeUrl = url,
                        message = "Google News returned a Cloudflare challenge",
                    )
                }
                FictionResult.Success(body)
            }
        } catch (e: IOException) {
            FictionResult.NetworkError("Google News fetch failed", e)
        }
    }

    /**
     * Inline Cloudflare challenge detection (#1446).
     * Checks for the three markers present in CF's JS challenge page.
     * Shared utility deferred to #1438.
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")
}
