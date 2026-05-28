package `in`.jphe.storyvox.source.royalroad.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

internal sealed interface FetchOutcome {
    data class Body(val html: String, val finalUrl: String) : FetchOutcome
    data class CloudflareChallenge(val url: String) : FetchOutcome
    data object NotFound : FetchOutcome
    data class HttpError(val code: Int, val message: String) : FetchOutcome
    data class RateLimited(val retryAfterSec: Int) : FetchOutcome
}

/**
 * Read wrapper that classifies common RR responses.
 *
 * Cloudflare detection: a managed challenge response is HTTP 403 with a body
 * containing "Just a moment..." or "/cdn-cgi/challenge-platform/" inline. We
 * surface this to the host so it can re-attempt the same URL inside a hidden
 * WebView (which solves the JS challenge), then refresh OkHttp's cookie jar
 * with the resulting cf_clearance.
 */
@Singleton
internal class RoyalRoadChallengeFetcher @Inject constructor(
    private val client: RateLimitedClient,
) {
    suspend fun fetchHtml(url: String): FetchOutcome = withContext(Dispatchers.IO) {
        try {
            client.get(url).use { resp -> classify(resp) }
        } catch (e: java.net.SocketTimeoutException) {
            FetchOutcome.HttpError(0, "Network timeout: ${e.message}")
        } catch (e: java.io.IOException) {
            FetchOutcome.HttpError(0, "Network error: ${e.message}")
        }
    }

    private fun classify(resp: Response): FetchOutcome {
        if (resp.code == 404) return FetchOutcome.NotFound
        if (resp.code == 429) {
            val retry = resp.header("Retry-After")?.toIntOrNull() ?: 30
            return FetchOutcome.RateLimited(retry)
        }
        val body = resp.body?.string().orEmpty()
        if (resp.code == 403 || resp.code == 503) {
            if (body.contains("/cdn-cgi/challenge-platform/") ||
                body.contains("Just a moment...") ||
                body.contains("cf-mitigated")
            ) {
                return FetchOutcome.CloudflareChallenge(resp.request.url.toString())
            }
            return FetchOutcome.HttpError(resp.code, resp.message)
        }
        if (!resp.isSuccessful) return FetchOutcome.HttpError(resp.code, resp.message)
        return FetchOutcome.Body(body, resp.request.url.toString())
    }
}
