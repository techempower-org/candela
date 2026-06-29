package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.data.dictionary.DictionaryRepository
import `in`.jphe.storyvox.data.dictionary.DictionaryResult
import `in`.jphe.storyvox.data.dictionary.WordDefinition
import `in`.jphe.storyvox.data.dictionary.lemmaCandidates
import `in`.jphe.storyvox.data.dictionary.normalizeLookupWord
import `in`.jphe.storyvox.data.dictionary.parseWiktionaryDefinitions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Issue #1230 — [DictionaryRepository] over the free Wiktionary REST API.
 *
 * Endpoint: `GET https://en.wiktionary.org/api/rest_v1/page/definition/{word}`
 * (no API key). Wiktionary is a Wikimedia property, so the request **must**
 * carry the descriptive User-Agent Wikimedia's policy requires — that's why
 * this lives in `:app` (the only module with `BuildConfig.VERSION_NAME`) and is
 * constructed with the shared `@UserAgentHeader` interceptor; an anonymous
 * client 403s. See `UserAgent` / `AppBindings.provideUserAgentInterceptor`.
 *
 * Parsing + word-normalisation are the pure, unit-tested helpers in
 * `:core-data`; this class is the thin IO seam over them. It never throws —
 * every failure maps to [DictionaryResult.Error] so the reader can render a
 * Retry row.
 *
 * Case fallback: Wiktionary titles are case-sensitive, so a sentence-initial
 * "The" must be retried as "the". [lemmaCandidates] enumerates the variants and
 * we try each until one resolves, capping at the (≤3) distinct forms so a
 * genuinely-unknown word costs at most a few quick 404s.
 */
class WiktionaryDictionaryRepository(
    private val client: OkHttpClient,
) : DictionaryRepository {

    override suspend fun define(word: String): DictionaryResult = withContext(Dispatchers.IO) {
        val lemma = normalizeLookupWord(word)
            ?: return@withContext DictionaryResult.NotFound(word.trim())

        var lastError: String? = null
        for (candidate in lemmaCandidates(lemma)) {
            when (val result = fetch(candidate)) {
                is DictionaryResult.Success -> return@withContext result
                is DictionaryResult.NotFound -> Unit // try the next case variant
                is DictionaryResult.Error -> lastError = result.message
            }
        }
        // Every candidate 404'd, or the last attempt errored. Prefer surfacing a
        // real network error (Retry-able) over a misleading "no definition".
        lastError?.let { DictionaryResult.Error(lemma, it) } ?: DictionaryResult.NotFound(lemma)
    }

    private fun fetch(lemma: String): DictionaryResult {
        val url = HttpUrl.Builder()
            .scheme("https")
            .host(WIKTIONARY_HOST)
            .addPathSegments("api/rest_v1/page/definition")
            .addPathSegment(lemma) // okhttp percent-encodes the single path segment
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> DictionaryResult.NotFound(lemma)
                    !response.isSuccessful -> DictionaryResult.Error(lemma, "HTTP ${response.code}")
                    else -> {
                        val entries = parseWiktionaryDefinitions(
                            body = response.body?.string().orEmpty(),
                            languageCode = "en",
                        )
                        if (entries.isEmpty()) {
                            DictionaryResult.NotFound(lemma)
                        } else {
                            DictionaryResult.Success(
                                WordDefinition(word = lemma, pronunciation = null, entries = entries),
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            DictionaryResult.Error(lemma, e.message ?: "Network error")
        }
    }

    private companion object {
        const val WIKTIONARY_HOST = "en.wiktionary.org"
    }
}
