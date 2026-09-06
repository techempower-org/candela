package `in`.jphe.storyvox.source.endless.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.endless.config.EndlessConfig
import `in`.jphe.storyvox.source.endless.config.EndlessConfigState
import `in`.jphe.storyvox.source.endless.di.EndlessLitrpgHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HTTP client over the endless-litrpg daemon's REST surface (spec §9.1).
 * Endpoints used:
 *
 *  - `GET /api/story` — serial metadata + the prompt hash.
 *  - `GET /api/chapters?since=0` — the chapter index (table of contents).
 *  - `GET /api/chapters/{n}` — one chapter's markdown + render manifest.
 *
 * Every call is `suspend`, pinned to [Dispatchers.IO] (#585), and resolves
 * to a typed [FictionResult] — an HTTP error or an IO failure is a return
 * value, never a throw. There is no auth arm because the daemon has no
 * auth, and no Cloudflare arm because a LAN daemon sits behind no CDN;
 * both would be dead code asserting a posture this backend doesn't have.
 *
 * **Base URL is resolved per request, not cached.** The user can retarget
 * the daemon in Settings while the app is running (katana → familiar), and
 * a cached base URL would keep a stale host alive until process death.
 *
 * **LAN-only enforcement.** [baseUrlOrNull] resolves the configured
 * authority and rejects anything that isn't loopback / link-local /
 * site-local, mirroring `PalaceDaemonApi.isLanLikeAuthority`. This is
 * defense in depth against a typo: the daemon speaks plain HTTP with no
 * auth, so a mistyped host must fail closed rather than send requests to
 * an arbitrary internet address in cleartext. Note this is an *app-layer*
 * guard, independent of the OS-layer cleartext gate — see the
 * `EndlessLitrpgSource` kdoc for the network-security-config status.
 */
@Singleton
internal open class EndlessLitrpgApi @Inject constructor(
    @EndlessLitrpgHttp private val client: OkHttpClient,
    private val config: EndlessConfig,
) {

    open suspend fun story(): FictionResult<EndlessStory> =
        get("/api/story", EndlessStory.serializer())

    /**
     * The chapter index. `since=0` asks for every chapter; the daemon
     * supports a watermark for incremental polling, which this module
     * doesn't need because
     * [`in`.jphe.storyvox.source.endless.EndlessLitrpgSource.latestRevisionToken]
     * already lets the poll worker skip the fetch entirely when nothing
     * has changed.
     */
    open suspend fun chapters(since: Int = 0): FictionResult<List<EndlessChapterEntry>> =
        get("/api/chapters?since=$since", CHAPTER_LIST)

    open suspend fun chapter(number: Int): FictionResult<EndlessChapter> =
        get("/api/chapters/$number", EndlessChapter.serializer())

    /**
     * Re-host a daemon-reported media path on the **configured** base URL.
     *
     * The daemon serves absolute URLs built from its own bound address
     * (`http://192.0.2.129:8093/media/0005.mp3`). Handing those straight to
     * ExoPlayer would quietly defeat the point of a configurable host: a
     * user who reaches the daemon through a hostname would still have
     * media fetched from the raw IP — a different authority, so it misses
     * any hostname-scoped cleartext allowlist and breaks the moment the
     * daemon's address changes without the client's knowledge. Keep the
     * daemon's *path*, re-host on the authority the user configured.
     *
     * Returns null when unconfigured, when the host fails the LAN guard,
     * or when [rawUrl] is null/blank — i.e. when the chapter has no
     * rendered audio. A null return means "treat this as a text chapter",
     * which is the correct degradation.
     */
    open suspend fun mediaUrl(rawUrl: String?): String? {
        if (rawUrl.isNullOrBlank()) return null
        val base = baseUrlOrNull(config.current()) ?: return null
        val path = pathOf(rawUrl) ?: return null
        return base + path
    }

    private suspend fun <T> get(
        path: String,
        serializer: kotlinx.serialization.DeserializationStrategy<T>,
    ): FictionResult<T> = withContext(Dispatchers.IO) {
        val cfg = config.current()
        if (!cfg.isConfigured) {
            return@withContext FictionResult.NetworkError(
                "Endless LitRPG: no daemon host configured — set one in " +
                    "Settings → Content sources.",
                IOException("host not configured"),
            )
        }
        val baseUrl = baseUrlOrNull(cfg)
            ?: return@withContext FictionResult.NetworkError(
                "Endless LitRPG: \"${cfg.host}\" is not a reachable LAN address.",
                IOException("host rejected: ${cfg.host}"),
            )

        val url = baseUrl + path
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound(
                        "Endless LitRPG: $path not found",
                    )
                    resp.code == 429 -> FictionResult.RateLimited(
                        retryAfter = null,
                        message = "Endless LitRPG rate limited (HTTP 429)",
                    )
                    // The challenge sniff MUST precede the auth arm below: a
                    // CF-gated 403 is not a login gate, and mapping it to
                    // AuthRequired sends the user to a sign-in prompt that
                    // cannot succeed (docs/CONTRIBUTING-SOURCES.md decision
                    // table). Unlikely for a LAN daemon — but this source can
                    // be pointed at a URL fronted by anything, and the check
                    // is one string scan on a path that has already failed.
                    resp.code == 403 && looksLikeChallenge(resp.peekBody(CHALLENGE_PEEK_BYTES).string()) ->
                        FictionResult.NetworkError(
                            "Endless LitRPG: got a bot-challenge page instead of the " +
                                "daemon — check the configured host.",
                            IOException("challenge interstitial"),
                        )
                    // 401/403 → AuthRequired per the shared decision table.
                    //
                    // The daemon itself has no auth (spec §9.1: every route
                    // open on a LAN port), so it can never emit these. If one
                    // arrives, something interposed on the path is demanding
                    // credentials — a reverse proxy, or a wrong host that
                    // resolved to somebody else's service. AuthRequired is the
                    // accurate typed report of "the transport wants a
                    // credential", and it is what the contract kit enforces
                    // for every HTTP source.
                    resp.code == 401 || resp.code == 403 -> FictionResult.AuthRequired(
                        "Endless LitRPG: HTTP ${resp.code} from $url — something in " +
                            "front of the daemon is requiring credentials.",
                    )
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "Endless LitRPG: HTTP ${resp.code} from $url",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@use FictionResult.NetworkError(
                                "Endless LitRPG: empty response body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(JSON.decodeFromString(serializer, text))
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(
                e.message ?: "Endless LitRPG: daemon unreachable",
                e,
            )
        } catch (e: SerializationException) {
            FictionResult.NetworkError(
                "Endless LitRPG returned an unexpected response shape",
                e,
            )
        }
    }

    /**
     * Normalize the user's host setting into a base URL, or null when it
     * isn't usable. Accepts `host`, `host:port`, and either scheme;
     * defaults to `http://` because the daemon has no TLS (the ESP32-C6
     * watch that shares this API cannot do TLS, so plain HTTP is
     * structural, not an oversight). A user who fronts the daemon with a
     * TLS proxy can type `https://…` and it is honoured.
     *
     * Rejects a value carrying a path — this class owns the path space.
     */
    internal fun baseUrlOrNull(cfg: EndlessConfigState): String? {
        val raw = cfg.host.trim().removeSuffix("/")
        if (raw.isEmpty()) return null

        // One case-insensitive strip, so `Http://host` can't slip through a
        // case-insensitive test paired with a case-sensitive removal — the
        // bug PalaceDaemonApi's kdoc records having shipped once.
        val schemeMatch = SCHEME_PREFIX.find(raw)
        val scheme = schemeMatch?.value?.lowercase() ?: "http://"
        val withoutScheme =
            if (schemeMatch != null) raw.substring(schemeMatch.range.last + 1) else raw
        if (withoutScheme.isEmpty()) return null
        if ('/' in withoutScheme) return null

        val candidate = scheme + withoutScheme
        val host = try {
            URI(candidate).host
        } catch (_: IllegalArgumentException) {
            return null
        } ?: return null

        return if (isLanLikeAuthority(host)) candidate else null
    }

    /**
     * True when [host] resolves somewhere that cannot leave the LAN.
     * `.local` short-circuits because mDNS names never resolve off-link
     * and resolving one can block; everything else is resolved and
     * inspected. An unresolvable host is "not LAN-like" — we can't prove
     * it's safe, so it fails closed.
     */
    private fun isLanLikeAuthority(host: String): Boolean {
        if (host.endsWith(".local", ignoreCase = true)) return true
        val addr = try {
            InetAddress.getByName(host)
        } catch (_: UnknownHostException) {
            return false
        }
        return addr.isLoopbackAddress ||
            addr.isSiteLocalAddress ||
            addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress
    }

    /**
     * Does a 403 body look like a bot-challenge interstitial rather than
     * the daemon's JSON? Keep this arm ahead of the 401/403 auth mapping
     * — see the call site.
     */
    private fun looksLikeChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated") ||
            body.contains("challenge-form")

    internal companion object {
        /** Enough of a 403 body to recognise a challenge page without
         *  buffering an arbitrarily large one. `peekBody` leaves the real
         *  body unconsumed for the arms below. */
        private const val CHALLENGE_PEEK_BYTES = 8L * 1024L

        internal val JSON = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        private val CHAPTER_LIST =
            kotlinx.serialization.builtins.ListSerializer(EndlessChapterEntry.serializer())

        private val SCHEME_PREFIX = Regex("^https?://", RegexOption.IGNORE_CASE)

        /**
         * The path (plus query, if any) of an absolute URL — or null if it
         * doesn't parse or carries no path. Used to re-host daemon-reported
         * media URLs on the configured authority; see [mediaUrl].
         */
        internal fun pathOf(rawUrl: String): String? {
            val uri = try {
                URI(rawUrl.trim())
            } catch (_: IllegalArgumentException) {
                return null
            }
            val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
            val query = uri.query
            return if (query.isNullOrBlank()) path else "$path?$query"
        }
    }
}
