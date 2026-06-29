package `in`.jphe.storyvox.source.googlenews.article

import `in`.jphe.storyvox.source.googlenews.di.GoogleNewsHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Issue #1295 — recovers the real publisher URL behind a Google News
 * `…/rss/articles/CBMi…` redirect.
 *
 * The post-2024 `CBMi…` id is an opaque token, not a base64'd URL, so the
 * only way to resolve it is Google's internal **`batchexecute` RPC**:
 *  1. GET the article page → scrape `data-n-a-sg` (signature) + `data-n-a-ts`
 *     (timestamp) off the `c-wiz` element.
 *  2. POST those + the id to `…/_/DotsSplashUi/data/batchexecute` (`Fbv4je`).
 *  3. Parse the anti-XSSI-prefixed JSON for the publisher URL.
 *
 * This is **fragile by nature** — Google changes the request/response shape
 * periodically and this will need maintenance when it does. Everything is
 * `runCatching`-guarded and returns null on any failure so the caller
 * ([GoogleNewsArticleResolver]) falls back to the headline digest.
 *
 * The four parsing/encoding steps are pure functions (no network, no Android)
 * so they're covered by `GoogleNewsUrlDecoderTest` against captured fixtures —
 * the network shell is the only untested part.
 */
internal open class GoogleNewsUrlDecoder @Inject constructor(
    @GoogleNewsHttp private val client: OkHttpClient,
) {

    /** Resolve [googleNewsUrl] (a `…/articles/CBMi…` link) to the publisher
     *  URL, or null on any failure. [locale] is the `gl:hl` pair embedded in
     *  the RPC (e.g. `US:en`). IO-pinned (#585). `open` so the resolver test
     *  can stub the network round-trip. */
    open suspend fun decode(googleNewsUrl: String, locale: String = DEFAULT_LOCALE): String? =
        withContext(Dispatchers.IO) {
            val id = extractArticleId(googleNewsUrl) ?: return@withContext null
            val pageHtml = runCatching { httpGet("$ARTICLE_BASE/$id") }.getOrNull()
                ?: return@withContext null
            val (signature, timestamp) = parseSignatureTimestamp(pageHtml)
                ?: return@withContext null
            val body = buildBatchExecuteBody(id, timestamp, signature, locale)
            val response = runCatching { httpPostForm(BATCH_URL, body) }.getOrNull()
                ?: return@withContext null
            parseBatchResponse(response)
        }

    private fun httpGet(url: String): String? =
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }

    private fun httpPostForm(url: String, formBody: String): String? {
        val req = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody(FORM_MEDIA_TYPE))
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    companion object {
        const val DEFAULT_LOCALE = "US:en"
        private const val ARTICLE_BASE = "https://news.google.com/rss/articles"
        private const val BATCH_URL =
            "https://news.google.com/_/DotsSplashUi/data/batchexecute"
        private val FORM_MEDIA_TYPE =
            "application/x-www-form-urlencoded;charset=UTF-8".toMediaType()

        private val ARTICLE_ID = Regex("""/articles/([^/?#]+)""")
        private val SIGNATURE = Regex("""data-n-a-sg="([^"]+)"""")
        private val TIMESTAMP = Regex("""data-n-a-ts="([^"]+)"""")
        private val HTTP_URL = Regex("""https?://[^\s"\\]+""")

        /** Pull the `CBMi…` id out of a `…/articles/<id>` link. */
        fun extractArticleId(url: String): String? =
            ARTICLE_ID.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        /** Scrape (signature, timestamp) from the article page HTML. */
        fun parseSignatureTimestamp(html: String): Pair<String, String>? {
            val sg = SIGNATURE.find(html)?.groupValues?.get(1)
            val ts = TIMESTAMP.find(html)?.groupValues?.get(1)
            return if (sg != null && ts != null) sg to ts else null
        }

        /** Build the `f.req=…` form body for the `Fbv4je` RPC. [locale] is the
         *  `gl:hl` pair (e.g. `US:en`). The inner request is a JSON string that
         *  we encode (quote + escape) and embed in the outer `[[[ ... ]]]`. */
        fun buildBatchExecuteBody(
            articleId: String,
            timestamp: String,
            signature: String,
            locale: String = DEFAULT_LOCALE,
        ): String {
            val inner =
                """["garturlreq",[["X","X",["X","X"],null,null,1,1,"$locale",null,1,null,null,null,null,null,0,1],"X","X",1,[1,1,1],1,1,null,0,0,null,0],"$articleId",$timestamp,"$signature"]"""
            // JsonPrimitive(...).toString() emits the JSON-quoted, escaped form
            // of the inner request string — exactly what the outer array needs.
            val innerEncoded = JsonPrimitive(inner).toString()
            val freq = """[[["Fbv4je",$innerEncoded]]]"""
            return "f.req=" + URLEncoder.encode(freq, "UTF-8")
        }

        /** Extract the publisher URL from the anti-XSSI-prefixed batchexecute
         *  response. Tries the structured `Fbv4je` payload first, then falls
         *  back to scanning for the first non-Google http(s) URL — deliberately
         *  lenient because Google reshuffles the inner array indices. */
        fun parseBatchResponse(body: String): String? {
            val start = body.indexOf('[')
            if (start >= 0) {
                val rows = runCatching {
                    Json.parseToJsonElement(body.substring(start)) as? JsonArray
                }.getOrNull()
                rows?.forEach { row ->
                    val arr = row as? JsonArray ?: return@forEach
                    val rpc = (arr.getOrNull(1) as? JsonPrimitive)?.contentOrNull
                    if (rpc == "Fbv4je") {
                        val payload = (arr.getOrNull(2) as? JsonPrimitive)?.contentOrNull
                        if (payload != null) firstPublisherUrl(payload)?.let { return it }
                    }
                }
            }
            return firstPublisherUrl(body)
        }

        /** First http(s) URL that isn't a Google/gstatic asset. */
        private fun firstPublisherUrl(s: String): String? =
            HTTP_URL.findAll(s).map { it.value.trim('"', ',', '\\') }
                .firstOrNull { url ->
                    !url.contains("google.com") && !url.contains("gstatic.com")
                }
    }
}
