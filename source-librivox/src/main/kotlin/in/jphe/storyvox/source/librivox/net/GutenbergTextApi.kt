package `in`.jphe.storyvox.source.librivox.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Issue #1046 — fetch the public-domain *text* that matches a LibriVox
 * recording from Project Gutenberg.
 *
 * Every LibriVox audiobook record carries a `url_text_source`
 * ([LibriVoxBook.urlTextSource]) — the source text the volunteers read
 * from, which for the overwhelming majority of the catalog is a Project
 * Gutenberg ebook page (`https://www.gutenberg.org/ebooks/<id>`). The
 * LibriVox source already streams the human narration through Media3
 * (audio-stream backend, issue #373); this client lets it ALSO surface
 * the underlying public-domain text so the reader's text pane can show
 * the book "alongside" the audio (and the TTS pipeline can narrate it,
 * since the text chapter carries no `audioUrl`).
 *
 * ## What it does
 *
 * 1. [parseGutenbergId] pulls the numeric ebook id out of a
 *    `url_text_source` when it points at gutenberg.org (the `ebooks`,
 *    `files`, `etext`, and `cache/epub` URL shapes). Non-Gutenberg
 *    sources (Wikisource, archive.org text, …) return `null` and the
 *    LibriVox source simply omits the text chapter rather than guessing.
 * 2. [fetchPlainText] downloads the UTF-8 plain-text rendering from
 *    `https://www.gutenberg.org/ebooks/<id>.txt.utf-8` (Gutenberg's
 *    stable "Plain Text UTF-8" alias, which 302-redirects to the
 *    `cache/epub/<id>/pg<id>.txt` file — the dedicated LibriVox client
 *    follows redirects), then [stripGutenbergBoilerplate] trims the
 *    `*** START/END OF THE PROJECT GUTENBERG EBOOK ***` license wrapper
 *    so the reader shows the work, not the legal preamble.
 *
 * The blocking OkHttp call is wrapped in `withContext(Dispatchers.IO)`
 * so a call from the main dispatcher can't trip
 * `NetworkOnMainThreadException` (the recurring `:source-*` footgun).
 */
@Singleton
internal class GutenbergTextApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /**
     * Download + clean the Gutenberg plain-text for [bookId]. The id is
     * the numeric Gutenberg ebook id (see [parseGutenbergId]), NOT the
     * LibriVox book id.
     */
    suspend fun fetchPlainText(bookId: String): FictionResult<String> = withContext(Dispatchers.IO) {
        val url = plainTextUrl(bookId)
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "text/plain")
                // gutenberg.org's robot policy asks clients to identify
                // themselves; reuse the LibriVox user-agent contact.
                .get()
                .build()
            // The blocking `execute()` below doesn't observe coroutine
            // cancellation on its own; tie the OkHttp call's lifetime to
            // this coroutine's Job so a cancelled scope (e.g. the reader
            // pane closes mid-fetch) aborts the in-flight request instead
            // of leaking the socket until the response completes.
            val call = client.newCall(request)
            coroutineContext[kotlinx.coroutines.Job]?.invokeOnCompletion { call.cancel() }
            call.execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 404 ->
                        FictionResult.NotFound("No Project Gutenberg text for ebook $bookId")
                    !resp.isSuccessful ->
                        FictionResult.NetworkError(
                            "HTTP ${resp.code} from gutenberg.org",
                            IOException("HTTP ${resp.code}"),
                        )
                    body.isBlank() ->
                        FictionResult.NetworkError(
                            "Empty Project Gutenberg response for ebook $bookId",
                            IOException("empty body"),
                        )
                    // #1442 — a Cloudflare challenge page served as
                    // HTTP 200 would be silently stored as chapter text
                    // and narrated by TTS. Detect it before it reaches
                    // the text pipeline.
                    looksLikeCfChallenge(body) ->
                        FictionResult.Cloudflare(
                            challengeUrl = url,
                            message = "Cloudflare challenge on Gutenberg ebook $bookId",
                        )
                    else -> FictionResult.Success(stripGutenbergBoilerplate(body))
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        }
    }

    companion object {
        const val BASE_URL: String = "https://www.gutenberg.org"

        // #1204 — UA applied via the shared @UserAgentHeader interceptor (UserAgent.kt).

        /**
         * Gutenberg's stable "Plain Text UTF-8" download alias. 302-
         * redirects to `cache/epub/<id>/pg<id>.txt`; the LibriVox OkHttp
         * client follows redirects, so a single URL is enough.
         */
        internal fun plainTextUrl(bookId: String): String =
            "$BASE_URL/ebooks/$bookId.txt.utf-8"

        /**
         * Extract the numeric Project Gutenberg ebook id from a LibriVox
         * `url_text_source`, or `null` when the URL isn't a Gutenberg
         * book (so the caller can skip the text chapter rather than
         * fetch a bogus id). Handles the catalog's URL shapes:
         *
         * - `https://www.gutenberg.org/ebooks/1342`
         * - `http://www.gutenberg.org/files/1342/1342-h/1342-h.htm`
         * - `https://www.gutenberg.org/etext/1342` (legacy)
         * - `https://www.gutenberg.org/cache/epub/1342/pg1342.txt`
         *
         * The trailing `(?:[/?#].*)?` tolerates sub-paths / query / hash.
         */
        internal fun parseGutenbergId(urlTextSource: String?): String? {
            val url = urlTextSource?.trim().orEmpty()
            if (url.isEmpty()) return null
            return GUTENBERG_ID_PATTERN.find(url)?.groupValues?.getOrNull(1)
        }

        private val GUTENBERG_ID_PATTERN: Regex = Regex(
            """^https?://(?:www\.)?gutenberg\.org/""" +
                """(?:ebooks|etext|files|cache/epub)/(\d+)(?:[/?#.].*)?$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Trim Project Gutenberg's license wrapper. PG plain-text files
         * fence the work between
         * `*** START OF THE PROJECT GUTENBERG EBOOK <title> ***` and
         * `*** END OF THE PROJECT GUTENBERG EBOOK <title> ***` (older
         * files say "THIS" instead of "THE", and spacing/case varies).
         * Returns the content between the markers; if either marker is
         * absent (a rare malformed file) the whole trimmed text is kept
         * so the reader still gets the book rather than nothing.
         */
        internal fun stripGutenbergBoilerplate(input: String): String {
            // Gutenberg plain-text files ship with CRLF (and the odd lone
            // CR) line endings. Normalize to `\n` first so the marker
            // line-slicing (`indexOf('\n', …)`) and the reader's paragraph
            // splitting both see a single, consistent line terminator.
            val raw = input.replace("\r\n", "\n").replace("\r", "\n")
            val startMatch = START_MARKER.find(raw)
            val afterStart = if (startMatch != null) {
                // Skip to the end of the marker's line.
                val nl = raw.indexOf('\n', startMatch.range.last)
                if (nl >= 0) raw.substring(nl + 1) else raw.substring(startMatch.range.last + 1)
            } else {
                raw
            }
            val endMatch = END_MARKER.find(afterStart)
            val body = if (endMatch != null) afterStart.substring(0, endMatch.range.first) else afterStart
            return body.trim()
        }

        private val START_MARKER: Regex = Regex(
            """\*\*\*\s*START OF (?:THE|THIS) PROJECT GUTENBERG EBOOK.*""",
            RegexOption.IGNORE_CASE,
        )
        private val END_MARKER: Regex = Regex(
            """\*\*\*\s*END OF (?:THE|THIS) PROJECT GUTENBERG EBOOK.*""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Issue #1442 — detect a Cloudflare challenge page that arrived
         * as HTTP 200. Without this check the challenge HTML would be
         * stored as chapter text and narrated by TTS — the worst failure
         * mode. Inlined per #1438 (no shared utility yet).
         */
        private fun looksLikeCfChallenge(body: String): Boolean =
            body.contains("/cdn-cgi/challenge-platform/") ||
                body.contains("Just a moment...") ||
                body.contains("cf-mitigated")
    }
}
