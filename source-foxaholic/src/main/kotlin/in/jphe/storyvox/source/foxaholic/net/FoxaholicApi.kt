package `in`.jphe.storyvox.source.foxaholic.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP client for Foxaholic. Every call is pinned to [Dispatchers.IO] (#585),
 * paced by [minIntervalMs], and resolves to exactly one typed [FictionResult]
 * per docs/CONTRIBUTING-SOURCES.md — the Cloudflare sniff runs BEFORE the
 * 401/403 auth arm so a CF-gated 403 reads as [FictionResult.Cloudflare].
 */
internal open class FoxaholicApi @Inject constructor(
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
    suspend fun get(pathOrUrl: String, accept: String = HTML): FictionResult<String> =
        execute(absolute(pathOrUrl)) { it.header("Accept", accept).get() }

    /** IO-pinned form POST (WordPress admin-ajax style endpoints). */
    suspend fun postForm(pathOrUrl: String, form: Map<String, String>): FictionResult<String> =
        execute(absolute(pathOrUrl)) { b ->
            val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
            b.header("Accept", "*/*").header("X-Requested-With", "XMLHttpRequest").post(body)
        }

    private suspend fun execute(
        url: String,
        build: (Request.Builder) -> Request.Builder,
    ): FictionResult<String> = withContext(Dispatchers.IO) {
        pace.withLock {
            val wait = lastRequestAt + minIntervalMs - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastRequestAt = System.currentTimeMillis()
        }
        try {
            client.newCall(build(Request.Builder().url(url)).build()).execute().use { classify(it, url) }
        } catch (e: IOException) {
            FictionResult.NetworkError("Foxaholic: ${e.message ?: "fetch failed"}", e)
        }
    }

    private fun classify(resp: Response, url: String): FictionResult<String> {
        val body = resp.body?.string().orEmpty()
        return when {
            looksLikeCfChallenge(resp, body) -> FictionResult.Cloudflare(
                challengeUrl = url,
                message = "Foxaholic is showing a Cloudflare check — try again later",
            )
            resp.code == 404 -> FictionResult.NotFound("Foxaholic: not found")
            resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired("HTTP ${resp.code} from Foxaholic")
            resp.code == 429 -> FictionResult.RateLimited(
                retryAfter = resp.header("Retry-After")?.toLongOrNull()?.seconds,
                message = "Foxaholic rate limited (HTTP 429)",
            )
            !resp.isSuccessful -> FictionResult.NetworkError(
                "HTTP ${resp.code} from Foxaholic",
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
        const val BASE_URL = "https://www.foxaholic.com"
        const val HTML = "text/html,application/xhtml+xml"
        const val JSON = "application/json"
        const val XML = "application/rss+xml,application/xml,text/xml"
    }
}
