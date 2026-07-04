package `in`.jphe.storyvox.source.googledrive.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * HTTP client for Google Drive. The [request] wrapper is pre-written to satisfy
 * the FictionSourceContractTest: it pins the blocking OkHttp call to
 * Dispatchers.IO (#585) and maps status codes to typed failures. Copy its shape
 * for every endpoint you add — do NOT surface raw HTTP codes or exceptions.
 */
internal open class GoogleDriveApi @Inject constructor(
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
                        resp.code == 404 -> FictionResult.NotFound("Google Drive: $path not found")
                        resp.code == 401 -> FictionResult.AuthRequired("HTTP 401 from $url")
                        resp.code == 403 -> {
                            // Cloudflare interstitials arrive as HTTP 403 with challenge
                            // HTML — the CF sniff MUST precede the auth mapping, or a
                            // CF-gated 403 misreports as "sign in required" (see
                            // docs/CONTRIBUTING-SOURCES.md decision table).
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "Google Drive returned a Cloudflare challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired("HTTP 403 from $url")
                            }
                        }
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "Google Drive rate limited (HTTP 429)",
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
            } catch (e: kotlinx.serialization.SerializationException) {
                // A throwing parse lambda must stay inside the typed-error
                // contract — never escape as a raw exception.
                FictionResult.NetworkError("Google Drive returned an unexpected response shape", e)
            }
        }

    /**
     * #1443-family heuristic: does a body look like a Cloudflare challenge
     * interstitial rather than your API's payload? Keep this arm ahead of
     * the 401/403 auth mapping (see the request() template above).
     */
    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val BASE_URL = "https://example.com"
    }
}
