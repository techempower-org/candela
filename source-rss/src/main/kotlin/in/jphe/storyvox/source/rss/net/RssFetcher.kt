package `in`.jphe.storyvox.source.rss.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #236 — thin wrapper around OkHttp for fetching feed XML.
 * Surfaces network/IO failures as [FictionResult.NetworkError] so
 * the source impl doesn't have to know about OkHttp.
 *
 * Honors If-Modified-Since / ETag if [previousEtag] / [previousLastModified]
 * are passed — a 304 Not Modified response surfaces as a Success
 * with [FetchResult.NotModified]. The caller can use this to skip
 * re-parsing when nothing's changed since the last poll.
 */
@Singleton
class RssFetcher @Inject constructor(
    @`in`.jphe.storyvox.source.rss.di.RssHttp private val client: OkHttpClient,
) {
    /**
     * Issue #585 — synchronous OkHttp `execute()` MUST run off the
     * main thread. `RssFetcher.fetch` is suspend but doesn't pin a
     * dispatcher, so the caller's context (often
     * `BrowseViewModel.viewModelScope` = `Dispatchers.Main.immediate`)
     * is inherited and the DNS lookup happens on the UI thread,
     * fatal `NetworkOnMainThreadException`. Same fix pattern as
     * `ArxivApi.getRaw`.
     */
    suspend fun fetch(
        url: String,
        previousEtag: String? = null,
        previousLastModified: String? = null,
    ): FictionResult<FetchResult> = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
            if (!previousEtag.isNullOrBlank()) builder.header("If-None-Match", previousEtag)
            if (!previousLastModified.isNullOrBlank()) builder.header("If-Modified-Since", previousLastModified)

            client.newCall(builder.build()).execute().use { response ->
                when {
                    response.code == 304 -> FictionResult.Success(FetchResult.NotModified)
                    !response.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${response.code}",
                        IOException("HTTP ${response.code} from $url"),
                    )
                    else -> {
                        val body = response.body?.string()
                            ?: return@use FictionResult.NetworkError("empty body", IOException("empty body from $url"))
                        FictionResult.Success(
                            FetchResult.Body(
                                xml = body,
                                etag = response.header("ETag"),
                                lastModified = response.header("Last-Modified"),
                            ),
                        )
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        // Identifies storyvox in feed-server logs so feed authors can
        // see who's pulling. Same posture move as the GitHub backend.
        // #1204 — UA applied via the shared @UserAgentHeader interceptor (UserAgent.kt).
    }
}

sealed interface FetchResult {
    data class Body(val xml: String, val etag: String?, val lastModified: String?) : FetchResult
    data object NotModified : FetchResult
}
