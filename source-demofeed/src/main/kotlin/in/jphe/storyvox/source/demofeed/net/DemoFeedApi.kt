package `in`.jphe.storyvox.source.demofeed.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP client for Demo Feed. The [request] wrapper is pre-written to satisfy
 * the FictionSourceContractTest: it pins the blocking OkHttp call to
 * Dispatchers.IO (#585) and maps status codes to typed failures. Copy its shape
 * for every endpoint you add — do NOT surface raw HTTP codes or exceptions.
 */
internal open class DemoFeedApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer without
     *  restructuring call sites (mirrors StandardEbooksApi.baseUrl). */
    internal open val baseUrl: String get() = BASE_URL

    /**
     * IO-pinned GET. `parse` turns the response body into your typed model.
     * Returns a typed [FictionResult] failure for every non-2xx status — never
     * throws for an HTTP error.
     */
    suspend fun <T> request(path: String, parse: (String) -> T): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val url = baseUrl + path
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> FictionResult.NotFound("Demo Feed: $path not found")
                        resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired(
                            "HTTP ${resp.code} from $url",
                        )
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "Demo Feed rate limited (HTTP 429)",
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
                            FictionResult.Success(parse(text))
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "fetch failed", e)
            }
        }

    companion object {
        const val BASE_URL = "https://example.com"
    }
}
