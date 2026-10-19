package `in`.jphe.storyvox.source.literotica.net

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
import java.net.URLEncoder
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP client for Literotica's JSON API (`/api/3/…`, the one the site's own
 * SPA calls — there is no documented public API). Every call is pinned to
 * [Dispatchers.IO] (#585), paced by [minIntervalMs], and resolves to exactly
 * one typed [FictionResult] per docs/CONTRIBUTING-SOURCES.md; the Cloudflare
 * sniff runs BEFORE the 401/403 auth arm.
 */
internal open class LiteroticaApi @Inject constructor(
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

    /** `/api/3` convention: one `params` query argument holding URL-encoded JSON. */
    fun apiPath(endpoint: String, paramsJson: String): String =
        "/api/3/${endpoint.trimStart('/')}?params=" + URLEncoder.encode(paramsJson, "UTF-8")

    /** IO-pinned GET of [pathOrUrl] (relative to [baseUrl] unless absolute). */
    suspend fun get(pathOrUrl: String): FictionResult<String> = withContext(Dispatchers.IO) {
        val url = absolute(pathOrUrl)
        pace.withLock {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/html;q=0.8")
                .get()
                .build()
            client.newCall(req).execute().use { classify(it, url) }
        } catch (e: IOException) {
            FictionResult.NetworkError("Literotica: ${e.message ?: "fetch failed"}", e)
        }
    }

    private fun classify(resp: Response, url: String): FictionResult<String> {
        val body = resp.body?.string().orEmpty()
        return when {
            looksLikeCfChallenge(resp, body) -> FictionResult.Cloudflare(
                challengeUrl = url,
                message = "Literotica is showing a Cloudflare check — try again later",
            )
            resp.code == 404 -> FictionResult.NotFound("Literotica: not found")
            resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired("HTTP ${resp.code} from Literotica")
            resp.code == 429 -> FictionResult.RateLimited(
                retryAfter = resp.header("Retry-After")?.toLongOrNull()?.seconds,
                message = "Literotica rate limited (HTTP 429)",
            )
            !resp.isSuccessful -> FictionResult.NetworkError(
                "HTTP ${resp.code} from Literotica",
                IOException("HTTP ${resp.code}"),
            )
            else -> FictionResult.Success(body)
        }
    }

    private fun looksLikeCfChallenge(resp: Response, body: String): Boolean {
        if (resp.header("cf-mitigated") != null) return true
        if (body.contains("/cdn-cgi/challenge-platform/")) return true
        val title = body.substringAfter("<title>", "").substringBefore("</title>")
        return title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true)
    }

    companion object {
        const val BASE_URL = "https://www.literotica.com"
    }
}
