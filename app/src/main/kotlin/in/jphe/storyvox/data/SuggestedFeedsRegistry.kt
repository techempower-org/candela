package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.BuildConfig
import `in`.jphe.storyvox.data.network.UserAgent
import `in`.jphe.storyvox.feature.api.SuggestedFeed
import `in`.jphe.storyvox.feature.api.SuggestedFeedKind
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Issue #246 — fetches the curated suggested-feeds list from
 * github.com/techempower-org/candela-feeds at runtime so new categories
 * + feeds land without an app rebuild.
 *
 * Schema (suggestions.json at repo root):
 * ```json
 * {
 *   "version": 1,
 *   "categories": [
 *     {
 *       "name": "Buddhist & dharma",
 *       "feeds": [
 *         { "title": "...", "description": "...", "url": "...", "kind": "Text" }
 *       ]
 *     }
 *   ]
 * }
 * ```
 *
 * Fetch policy:
 *  - Lazy: only fires when the user opens Settings → RSS → Suggested
 *    feeds. The Flow returns the baked-in fallback immediately, then
 *    emits the remote list once the fetch resolves.
 *  - Per-process cache: result memoized in a property; subsequent
 *    observations get the cached list without re-fetching. Cold start
 *    of the app process triggers a fresh fetch.
 *  - On failure (network down, parse error, schema version mismatch),
 *    falls back to the baked-in seed list. Future-self note: a
 *    DataStore-backed disk cache + 24h TTL is the v0.4.67 follow-up
 *    if startup latency on cold-app-launch becomes annoying.
 */
@Singleton
class SuggestedFeedsRegistry @Inject constructor() {
    // Dedicated OkHttp client — same kind of "side channel for static
    // metadata" pattern as the per-source clients elsewhere; constructs
    // here rather than injecting because the registry call site is the
    // only consumer.
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var cached: List<SuggestedFeed>? = null

    fun observe(fallback: List<SuggestedFeed>): Flow<List<SuggestedFeed>> = flow {
        cached?.let { emit(it); return@flow }
        emit(fallback)
        val fetched = fetchOrNull()
        if (fetched != null && fetched.isNotEmpty()) {
            cached = fetched
            emit(fetched)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchOrNull(): List<SuggestedFeed>? {
        return try {
            val request = Request.Builder()
                .url(REGISTRY_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                parse(body)
            }
        } catch (_: IOException) {
            null
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun parse(json: String): List<SuggestedFeed>? {
        val root = JSONObject(json)
        val version = root.optInt("version", 0)
        // Schema-version gate — if the registry bumps to a shape we
        // don't know, fall back rather than rendering garbage.
        if (version != SUPPORTED_SCHEMA_VERSION) return null
        val categories = root.optJSONArray("categories") ?: return null
        val out = mutableListOf<SuggestedFeed>()
        for (i in 0 until categories.length()) {
            val cat = categories.getJSONObject(i)
            val name = cat.optString("name").takeIf { it.isNotBlank() } ?: continue
            val feeds = cat.optJSONArray("feeds") ?: continue
            for (j in 0 until feeds.length()) {
                val f = feeds.getJSONObject(j)
                val title = f.optString("title").takeIf { it.isNotBlank() } ?: continue
                val url = f.optString("url").takeIf { it.isNotBlank() } ?: continue
                val description = f.optString("description")
                val kindStr = f.optString("kind")
                val kind = when (kindStr) {
                    "Text" -> SuggestedFeedKind.Text
                    "AudioPodcast" -> SuggestedFeedKind.AudioPodcast
                    else -> SuggestedFeedKind.Text
                }
                out += SuggestedFeed(
                    title = title,
                    description = description,
                    url = url,
                    category = name,
                    kind = kind,
                )
            }
        }
        return out
    }

    companion object {
        const val REGISTRY_URL =
            "https://raw.githubusercontent.com/techempower-org/candela-feeds/main/suggestions.json"
        /**
         * Descriptive User-Agent built from the centralized [UserAgent]
         * tokens + the live build version (#1216). `:app` carries
         * `BuildConfig.VERSION_NAME`, so this can use the full
         * `UserAgent.format()` shape. Replaces the stale pre-rebrand
         * `storyvox/1.0 (jphein)` string.
         */
        val USER_AGENT: String = UserAgent.format(BuildConfig.VERSION_NAME)
        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}
