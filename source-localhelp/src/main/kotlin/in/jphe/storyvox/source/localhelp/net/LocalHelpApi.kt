package `in`.jphe.storyvox.source.localhelp.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.localhelp.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/**
 * Issue #1465 — HTTP client for the **211 National Data Platform Search API**
 * (United Way Worldwide, `https://api.211.org/search/v1`, documented at
 * apiportal.211.org). Two operations are used:
 *
 *  - `GET /api/Search/Keyword` — keyword + optional location (city, ZIP,
 *    county, address). With no location the API geocodes the caller's IP, which
 *    for an on-device request is the listener's own area — so "near me" works
 *    with zero permissions.
 *  - `GET /api/ServiceAtLocation/v2` — detail (hours, phones, eligibility …)
 *    for one `idServiceAtLocation` from a search result.
 *
 * Auth is an Azure APIM subscription key in the `Api-Key` header. The key is
 * build-time ([BuildConfig.TWO11_API_KEY], from local.properties / the CI
 * secret). With no key this client issues **no** requests and returns a typed
 * "not configured" failure — the source is also `defaultEnabled = false`, so a
 * keyless build never shows it unless the user switches it on.
 *
 * The [request] wrapper keeps the scaffold's contract shape: IO-pinned (#585),
 * every non-2xx mapped to a typed [FictionResult] failure, and a Cloudflare /
 * interstitial sniff ahead of the 403 → auth mapping.
 */
internal open class LocalHelpApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer. */
    internal open val baseUrl: String get() = BASE_URL

    /** The 211 NDP subscription key, or blank when this build has none.
     *  `open` so the contract test can inject a non-blank fake (#1529). */
    internal open fun apiKey(): String = BuildConfig.TWO11_API_KEY

    /** True when a key is compiled in — requests can actually be made. */
    val isConfigured: Boolean get() = apiKey().isNotBlank()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Keyword search. [location] blank → omitted (the API geocodes the caller's
     * IP). [distanceMiles] null → API default. [skip]/[top] page the results.
     */
    suspend fun keywordSearch(
        keyword: String,
        location: String?,
        distanceMiles: Int?,
        orderByDistance: Boolean,
        skip: Int,
        top: Int,
    ): FictionResult<Two11SearchResponse> {
        val url = urlFor(KEYWORD_PATH) {
            addQueryParameter("Keyword", keyword)
            if (!location.isNullOrBlank()) addQueryParameter("Location", location.trim())
            if (distanceMiles != null) addQueryParameter("Distance", distanceMiles.toString())
            addQueryParameter("OrderBy", if (orderByDistance) "distance" else "relevance")
            addQueryParameter("SearchMode", "any")
            addQueryParameter("Skip", skip.toString())
            addQueryParameter("Top", top.toString())
        }
        return request(url) { json.decodeFromString(Two11SearchResponse.serializer(), it) }
    }

    /** Service-at-location detail, as a raw JSON tree — the v2 payload shape
     *  isn't published anonymously, so [Two11Narration] reads it tolerantly. */
    suspend fun serviceAtLocation(idServiceAtLocation: String): FictionResult<JsonElement> {
        val url = urlFor(DETAIL_PATH) { addQueryParameter("idServiceAtLocation", idServiceAtLocation) }
        return request(url) { json.parseToJsonElement(it) }
    }

    private fun urlFor(path: String, params: HttpUrl.Builder.() -> Unit): HttpUrl =
        (baseUrl.trimEnd('/') + path).toHttpUrl().newBuilder().apply(params).build()

    /**
     * IO-pinned keyed GET. Returns a typed "not configured" failure (and makes
     * no request) when there is no key; otherwise maps every status per the
     * CONTRIBUTING-SOURCES decision table — never throws for an HTTP error.
     */
    suspend fun <T> request(url: HttpUrl, parse: (String) -> T): FictionResult<T> =
        withContext(Dispatchers.IO) {
            val key = apiKey()
            if (key.isBlank()) {
                return@withContext FictionResult.NetworkError(NOT_CONFIGURED, IOException(NOT_CONFIGURED))
            }
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Api-Key", key)
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 404 -> FictionResult.NotFound("211: ${url.encodedPath} not found")
                        resp.code == 401 -> FictionResult.AuthRequired(
                            "The 211 directory rejected this build's API key (HTTP 401)",
                        )
                        resp.code == 403 -> {
                            // CF sniff MUST precede the auth mapping — a
                            // challenge page is not a login gate.
                            val body = resp.body?.string().orEmpty()
                            if (looksLikeCfChallenge(body)) {
                                FictionResult.NetworkError(
                                    "The 211 directory returned a challenge page — try again later",
                                    IOException("Cloudflare challenge"),
                                )
                            } else {
                                FictionResult.AuthRequired(
                                    "This build's 211 API key has no access to that data (HTTP 403)",
                                )
                            }
                        }
                        // APIM enforces 10 calls/min and 1,000/day per key.
                        resp.code == 429 -> FictionResult.RateLimited(
                            retryAfter = null,
                            message = "The 211 directory is busy — try again in a minute (HTTP 429)",
                        )
                        !resp.isSuccessful -> FictionResult.NetworkError(
                            "HTTP ${resp.code} from the 211 directory",
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
                FictionResult.NetworkError("The 211 directory returned an unexpected response shape", e)
            } catch (e: IllegalArgumentException) {
                // kotlinx JsonDecodingException extends SerializationException,
                // but decodeFromString can also surface IAE for type mismatches.
                FictionResult.NetworkError("The 211 directory returned an unexpected response shape", e)
            }
        }

    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("/cdn-cgi/challenge") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    companion object {
        const val BASE_URL = "https://api.211.org/search/v1"
        const val KEYWORD_PATH = "/api/Search/Keyword"
        const val DETAIL_PATH = "/api/ServiceAtLocation/v2"
        const val NOT_CONFIGURED =
            "The 211 local-help directory isn't set up in this build. You can still call or text 2-1-1."
    }
}
