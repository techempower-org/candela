package `in`.jphe.storyvox.source.palace.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.palace.parse.OpdsFeed
import `in`.jphe.storyvox.source.palace.parse.OpdsParseException
import `in`.jphe.storyvox.source.palace.parse.OpdsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #502 — HTTP transport for Palace Project OPDS catalog feeds.
 *
 * Palace Project libraries expose their catalog at well-known OPDS
 * endpoints under the user-configured library base URL
 * (e.g., `https://circulation.openebooks.us` or per-library subdomains
 * like `nypl.palaceproject.io`). The base URL is the OPDS root feed; we
 * fetch it once on startup-of-source, then walk the navigation links
 * inside it to populate genre / collection lanes.
 *
 * v1 endpoints consumed:
 *
 *  - `GET <base>` — root OPDS feed. Mix of navigation entries (links to
 *    sub-feeds) and sometimes immediate publication rows. The root's
 *    title becomes the library's display name in the storyvox plugin
 *    manager card.
 *  - `GET <feedHref>` — generic sub-feed fetch. Same parser, different
 *    URL.
 *  - `GET <acquisitionHref>` — EPUB binary download (only invoked when
 *    the entry's acquisition link has `type=application/epub+zip` and
 *    `rel=…/acquisition/open-access`). The result is a ByteArray the
 *    caller writes to disk + parses through `:source-epub`.
 *
 * **What this client does not do:**
 *  - No auth header. Library-card sign-in is a follow-up surface (Palace
 *    libraries DO support a basic-auth challenge for borrow flows, but
 *    v1 storyvox doesn't borrow; we only read the public catalog and
 *    download open-access titles).
 *  - No OPDS 2.x JSON. The parser would throw [OpdsParseException]
 *    immediately if a library returned JSON.
 *  - No LCP license fetch. DRM'd acquisition links surface a deep-link
 *    CTA; this client never invokes their `href`.
 *
 * Sends a polite User-Agent identifying as storyvox with a contact URL
 * — same etiquette as the Gutenberg / Standard Ebooks clients.
 */
@Singleton
internal class PalaceApi @Inject constructor(
    private val client: OkHttpClient,
) {

    /**
     * Fetch + parse the OPDS feed at [url]. The [url] should already be
     * absolute (the parser resolves relative hrefs from a base URL so
     * call sites generally pass the result of a previous `nextHref` /
     * navigation `href` straight through).
     */
    suspend fun fetchFeed(url: String): FictionResult<OpdsFeed> =
        withContext(Dispatchers.IO) {
            when (val r = fetchString(url)) {
                is FictionResult.Success -> {
                    try {
                        FictionResult.Success(OpdsParser.parse(r.value, baseUrl = url))
                    } catch (e: OpdsParseException) {
                        // The catalog walker turns every parse failure
                        // into a NetworkError (the surface error type
                        // the BrowsePaginator already renders cleanly).
                        // We keep the original message so the developer
                        // can see "this library is OPDS 2.x" or "feed
                        // missing required <title>" in logcat.
                        FictionResult.NetworkError(
                            "Could not parse OPDS feed from $url: ${e.message}",
                            e,
                        )
                    }
                }
                is FictionResult.Failure -> r
            }
        }

    /**
     * Download the bytes at [url] as an EPUB. Caller is responsible for
     * having confirmed this is an `application/epub+zip` open-access
     * acquisition link — this method does no MIME validation beyond
     * what HTTP returns. Same shape as `:source-gutenberg`'s
     * downloadEpub.
     */
    suspend fun downloadEpub(url: String): FictionResult<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("Accept", "application/epub+zip")
                    .get()
                    .build()
                client.newCall(req).execute().use { resp ->
                    when {
                        resp.code == 401 || resp.code == 403 ->
                            // 401/403 on an open-access link usually
                            // means the catalog mislabelled a DRM'd
                            // title or the library required a session
                            // we don't have. Surface as AuthRequired so
                            // the UI can prompt the user to switch to
                            // the Palace app for this title.
                            FictionResult.AuthRequired(
                                "Palace library returned ${resp.code} on $url — " +
                                    "title may require a borrow session.",
                            )
                        resp.code == 404 ->
                            FictionResult.NotFound("EPUB not available at $url")
                        !resp.isSuccessful ->
                            FictionResult.NetworkError(
                                "HTTP ${resp.code} downloading Palace EPUB",
                                IOException("HTTP ${resp.code}"),
                            )
                        else -> {
                            val bytes = resp.body?.bytes()
                                ?: return@withContext FictionResult.NetworkError(
                                    "empty EPUB body from $url",
                                    IOException("empty body"),
                                )
                            FictionResult.Success(bytes)
                        }
                    }
                }
            } catch (e: IOException) {
                FictionResult.NetworkError(e.message ?: "Palace EPUB download failed", e)
            }
        }

    /**
     * GET [url] and return the body as a string, mapping HTTP failure
     * shapes to the right [FictionResult.Failure] subtypes so the
     * BrowsePaginator + DetailViewModel UIs render the correct copy.
     */
    private fun fetchString(url: String): FictionResult<String> {
        return try {
            val req = Request.Builder()
                .url(url)
                .header(
                    "Accept",
                    // OPDS 1.x catalog feeds advertise this content
                    // type; libraries that publish 2.x will return JSON
                    // and the parser will reject it (logged + deferred).
                    "application/atom+xml;profile=opds-catalog, application/atom+xml, " +
                        "application/xml;q=0.9, */*;q=0.1",
                )
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 401 -> FictionResult.AuthRequired(
                        "Palace library at $url requires sign-in.",
                    )
                    resp.code == 404 -> FictionResult.NotFound("Palace: $url not found")
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from Palace at $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return FictionResult.NetworkError(
                                "empty body from $url",
                                IOException("empty body"),
                            )
                        FictionResult.Success(text)
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "Palace fetch failed", e)
        }
    }

    companion object {
        /**
         * Identifies storyvox to Palace library operators. The contact
         * URL routes any rate-limit / abuse signal back to a real human
         * — same etiquette as the Gutenberg / Standard Ebooks clients.
         */
        // #1204 — UA applied via the shared @UserAgentHeader interceptor (UserAgent.kt).
    }
}
