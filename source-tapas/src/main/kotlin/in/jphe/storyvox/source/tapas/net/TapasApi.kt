package `in`.jphe.storyvox.source.tapas.net

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
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP client for Tapas. Every call is pinned to [Dispatchers.IO] (#585),
 * paced by [minIntervalMs], and resolves to exactly one typed [FictionResult]
 * per docs/CONTRIBUTING-SOURCES.md — the challenge sniff runs BEFORE the
 * 401/403 auth arm.
 */
internal open class TapasApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so contract tests retarget a MockWebServer. */
    internal open val baseUrl: String get() = BASE_URL

    /** Minimum spacing between requests (be a polite client). */
    internal open val minIntervalMs: Long get() = 1500L

    private val pace = Mutex()
    @Volatile private var lastRequestAt = 0L

    fun absolute(pathOrUrl: String): String = when {
        pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
        pathOrUrl.startsWith("//") -> "https:$pathOrUrl"
        else -> baseUrl.trimEnd('/') + "/" + pathOrUrl.trimStart('/')
    }

    /** IO-pinned GET of [pathOrUrl] (relative to [baseUrl] unless absolute). */
    suspend fun get(
        pathOrUrl: String,
        accept: String = HTML,
        xhr: Boolean = false,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        val url = absolute(pathOrUrl)
        pace.withLock {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .apply { if (xhr) header("X-Requested-With", "XMLHttpRequest") }
                .get()
                .build()
            client.newCall(req).execute().use { classify(it, url) }
        } catch (e: IOException) {
            FictionResult.NetworkError("Tapas: ${e.message ?: "fetch failed"}", e)
        }
    }

    private fun classify(resp: Response, url: String): FictionResult<String> {
        val body = resp.body?.string().orEmpty()
        return when {
            looksLikeChallenge(resp, body) -> FictionResult.Cloudflare(
                challengeUrl = url,
                message = "Tapas is showing a browser check — try again later",
            )
            resp.code == 404 -> FictionResult.NotFound("Tapas: not found")
            resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired("HTTP ${resp.code} from Tapas")
            resp.code == 429 -> FictionResult.RateLimited(
                retryAfter = resp.header("Retry-After")?.toLongOrNull()?.seconds,
                message = "Tapas rate limited (HTTP 429)",
            )
            !resp.isSuccessful -> FictionResult.NetworkError(
                "HTTP ${resp.code} from Tapas",
                IOException("HTTP ${resp.code}"),
            )
            else -> FictionResult.Success(body)
        }
    }

    protected open fun looksLikeChallenge(resp: Response, body: String): Boolean {
        if (resp.header("cf-mitigated") != null) return true
        if (body.contains("/cdn-cgi/challenge-platform/")) return true
        val title = body.substringAfter("<title>", "").substringBefore("</title>")
        return title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true)
    }

    companion object {
        const val BASE_URL = "https://tapas.io"
        const val HTML = "text/html,application/xhtml+xml"
        const val JSON = "application/json"
    }
}
