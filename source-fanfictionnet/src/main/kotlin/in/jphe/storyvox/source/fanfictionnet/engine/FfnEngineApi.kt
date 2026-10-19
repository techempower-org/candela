package `in`.jphe.storyvox.source.fanfictionnet.engine

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP client shared by the two sites that run the FanFiction.net engine
 * (FanFiction.net and FictionPress). Public so `:source-fictionpress` can
 * reuse it — the same precedent as `:source-ao3` → `:source-epub`.
 *
 * Every call is pinned to [Dispatchers.IO] (#585) and resolves to exactly one
 * typed [FictionResult] per the CONTRIBUTING-SOURCES decision table. The
 * Cloudflare sniff runs BEFORE the 401/403 auth arm: both sites sit behind
 * Cloudflare's managed challenge, and a CF-gated 403 must read as
 * [FictionResult.Cloudflare], never "sign in required".
 *
 * Requests are paced ([minIntervalMs]) — both sites publish
 * `crawl-delay: 5` in robots.txt; interactive browsing doesn't need the full
 * five seconds, but we never burst.
 */
open class FfnEngineApi(
    private val client: OkHttpClient,
    private val siteName: String,
    private val defaultBaseUrl: String,
) {
    /** Test seam — `open` so contract tests retarget a MockWebServer. */
    open val baseUrl: String get() = defaultBaseUrl

    /** Minimum spacing between requests. Tests may override to 0. */
    open val minIntervalMs: Long get() = 1_500L

    private val pace = Mutex()
    @Volatile private var lastRequestAt = 0L

    fun absolute(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
        pathOrUrl.startsWith("//") -> "https:$pathOrUrl"
        else -> baseUrl.trimEnd('/') + "/" + pathOrUrl.trimStart('/')
    }

    /** IO-pinned GET returning the raw HTML body as a typed result. */
    suspend fun getHtml(path: String): FictionResult<String> = withContext(Dispatchers.IO) {
        val url = absolute(path)
        pace.withLock {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "text/html,application/xhtml+xml")
                .get()
                .build()
            client.newCall(req).execute().use { classify(it, url) }
        } catch (e: IOException) {
            FictionResult.NetworkError("$siteName: ${e.message ?: "fetch failed"}", e)
        }
    }

    private fun classify(resp: Response, url: String): FictionResult<String> {
        val body = resp.body?.string().orEmpty()
        return when {
            looksLikeCfChallenge(resp, body) -> FictionResult.Cloudflare(
                challengeUrl = url,
                message = "$siteName is behind a Cloudflare check right now — try again later",
            )
            resp.code == 404 -> FictionResult.NotFound("$siteName: page not found")
            resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired("HTTP ${resp.code} from $siteName")
            resp.code == 429 -> FictionResult.RateLimited(
                retryAfter = resp.header("Retry-After")?.toLongOrNull()?.seconds,
                message = "$siteName rate limited (HTTP 429)",
            )
            !resp.isSuccessful -> FictionResult.NetworkError(
                "HTTP ${resp.code} from $siteName",
                IOException("HTTP ${resp.code}"),
            )
            body.isBlank() -> FictionResult.NetworkError("$siteName: empty body", IOException("empty body"))
            else -> FictionResult.Success(body)
        }
    }

    companion object {
        fun looksLikeCfChallenge(resp: Response, body: String): Boolean {
            if (resp.header("cf-mitigated") != null) return true
            if (body.contains("/cdn-cgi/challenge-platform/")) return true
            val title = body.substringAfter("<title>", "").substringBefore("</title>")
            return title.contains("Just a moment", ignoreCase = true) ||
                title.contains("Attention Required", ignoreCase = true)
        }
    }
}
