package `in`.jphe.storyvox.source.bookshare.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder

/**
 * Issue #1002 — minimal client for the Bookshare API v2
 * (https://apidocs.bookshare.org). Covers the discovery surface the Browse
 * sheet needs: title search/browse + category listing. Content **download** is
 * deliberately NOT here — Bookshare copyrighted titles are Protected DAISY
 * (PDTB), gated on the partnership + per-user decryption (see #1002).
 *
 * Every endpoint takes the partner `api_key` (request param) and, optionally, a
 * per-user OAuth bearer token (member-scoped endpoints). 401/403 map to
 * [FictionResult.AuthRequired] so a missing/invalid key or sign-in surfaces as
 * the interface's graceful auth path rather than a crash. Mirrors
 * :source-gutenberg's GutendexApi (sync OkHttp on Dispatchers.IO; lenient Json).
 */
internal class BookshareApi(
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** `GET /v2/titles` — search / browse by title, author, and/or category. */
    suspend fun searchTitles(
        apiKey: String,
        accessToken: String?,
        title: String? = null,
        author: String? = null,
        category: String? = null,
        limit: Int = DEFAULT_LIMIT,
        start: String? = null,
    ): FictionResult<BookshareTitlesPage> =
        request(titlesPath(title, author, category, limit, start, apiKey), accessToken) {
            json.decodeFromString<BookshareTitlesPage>(it)
        }

    /** `GET /v2/titles/categories` — subject list for the genre picker. */
    suspend fun categories(
        apiKey: String,
        accessToken: String?,
    ): FictionResult<BookshareCategoriesPage> =
        request("/v2/titles/categories?api_key=${enc(apiKey)}", accessToken) {
            json.decodeFromString<BookshareCategoriesPage>(it)
        }

    /**
     * Sync OkHttp `execute()` wrapped in `withContext(Dispatchers.IO)` — a bare
     * `suspend` doesn't move the call off the caller's thread (would crash with
     * NetworkOnMainThreadException). Same posture as the other network sources.
     */
    private suspend fun <T> request(
        path: String,
        accessToken: String?,
        parse: (String) -> T,
    ): FictionResult<T> = withContext(Dispatchers.IO) {
        val url = BASE_URL + path
        try {
            val builder = Request.Builder().url(url).header("Accept", "application/json").get()
            if (!accessToken.isNullOrBlank()) builder.header("Authorization", "Bearer $accessToken")
            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired(
                        "Bookshare rejected the request (HTTP ${resp.code}) — API key or sign-in required.",
                    )
                    resp.code == 404 -> FictionResult.NotFound("Bookshare: $path not found")
                    resp.code == 429 -> FictionResult.RateLimited(null, "Bookshare rate limited")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from $url", IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError("empty body", IOException("empty body"))
                        FictionResult.Success(parse(text))
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            FictionResult.NetworkError("Bookshare returned unexpected JSON shape", e)
        }
    }

    companion object {
        const val BASE_URL = "https://api.bookshare.org"
        const val DEFAULT_LIMIT = 24

        private fun enc(s: String): String = URLEncoder.encode(s, Charsets.UTF_8)

        /**
         * Build `/v2/titles?…` with whichever filters are present, in a
         * deterministic order (title → author → categories → limit → start →
         * api_key) so URL-shape tests can pin exact strings. Exposed
         * package-internal for unit-test pinning.
         */
        internal fun titlesPath(
            title: String?,
            author: String?,
            category: String?,
            limit: Int,
            start: String?,
            apiKey: String,
        ): String {
            val params = mutableListOf<Pair<String, String>>()
            title?.takeIf { it.isNotBlank() }?.let { params += "title" to it }
            author?.takeIf { it.isNotBlank() }?.let { params += "author" to it }
            category?.takeIf { it.isNotBlank() }?.let { params += "categories" to it }
            params += "limit" to limit.toString()
            start?.takeIf { it.isNotBlank() }?.let { params += "start" to it }
            params += "api_key" to apiKey
            val qs = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
            return "/v2/titles?$qs"
        }
    }
}

// ── Wire types ──────────────────────────────────────────────────────────────

@Serializable
internal data class BookshareTitlesPage(
    val totalResults: Int = 0,
    val limit: Int = 0,
    val next: String? = null,
    val titles: List<BookshareTitle> = emptyList(),
)

@Serializable
internal data class BookshareTitle(
    val bookshareId: Long = 0L,
    val title: String = "",
    val authors: List<BookshareAuthor> = emptyList(),
    val isbn13: String? = null,
    val categories: List<BookshareCategory> = emptyList(),
) {
    /** Comma-joined author display ("First Last"), or a fallback. */
    fun authorDisplay(): String =
        authors.mapNotNull { it.display().takeIf(String::isNotBlank) }
            .joinToString(", ")
            .ifBlank { "Unknown author" }
}

@Serializable
internal data class BookshareAuthor(
    val firstName: String? = null,
    val lastName: String? = null,
) {
    fun display(): String =
        listOfNotNull(firstName?.takeIf { it.isNotBlank() }, lastName?.takeIf { it.isNotBlank() })
            .joinToString(" ")
}

@Serializable
internal data class BookshareCategoriesPage(
    val categories: List<BookshareCategory> = emptyList(),
)

@Serializable
internal data class BookshareCategory(
    val name: String = "",
    val description: String? = null,
)
