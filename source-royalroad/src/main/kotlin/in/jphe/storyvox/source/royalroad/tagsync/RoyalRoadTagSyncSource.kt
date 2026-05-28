package `in`.jphe.storyvox.source.royalroad.tagsync

import `in`.jphe.storyvox.source.royalroad.net.RoyalRoadChallengeFetcher
import `in`.jphe.storyvox.source.royalroad.net.FetchOutcome
import `in`.jphe.storyvox.source.royalroad.net.RateLimitedClient
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.FormBody

/**
 * Two-way Royal Road saved-tags mirror (issue #178).
 *
 * **Read direction** ([fetchSavedTags]): GETs the saved-filter
 * search page, parses the user's preferred tag set + antiforgery
 * token. Returns [Result.NotAuthenticated] when the session is
 * missing — the syncer treats that as "skip this round, the user
 * will sign in eventually."
 *
 * **Write direction** ([pushSavedTags]): POSTs back the canonical
 * set, gated on a fresh token from [fetchSavedTags]. The endpoint
 * shape is documented (with the verified-by-real-RR uncertainty
 * called out) in `scratch/rr-tag-sync/api-notes.md`. Any 2xx/3xx
 * is success; any 4xx (other than 401) is treated as "endpoint
 * shape changed, skip writes until the next 24h window."
 *
 * The 24h cadence is chosen because:
 *  - RR's `robots.txt` plus our 1 req/sec [RateLimitedClient]
 *    floor already keep us well under any plausible budget.
 *  - The user's saved-tag set isn't a hot-path preference — a 24h
 *    propagation delay is acceptable for the most common case
 *    (sign in on tablet, see tablet's saved tags appear on phone
 *    the next day).
 *  - Per-action immediate writes (the "fire on every follow/
 *    unfollow" branch) make a best-effort POST too; the 24h
 *    periodic exists as the backstop when the immediate write
 *    fails network-side. See [RoyalRoadTagSyncOutcome].
 */
@Singleton
internal class RoyalRoadTagSyncSource @Inject constructor(
    private val fetcher: RoyalRoadChallengeFetcher,
    private val client: RateLimitedClient,
) {

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data object NotAuthenticated : Result<Nothing>
        data class Error(val message: String) : Result<Nothing>
    }

    /**
     * Pull the user's saved-tag set + a fresh CSRF token from RR.
     * Returns the parsed [SavedTagsParser.Parsed] payload on
     * success.
     */
    suspend fun fetchSavedTags(): Result<SavedTagsParser.Parsed> =
        when (val outcome = fetcher.fetchHtml(RoyalRoadTagSyncEndpoints.readUrl)) {
            is FetchOutcome.Body -> when (
                val parsed = SavedTagsParser.parse(outcome.html, outcome.finalUrl)
            ) {
                is SavedTagsParser.Result.Ok -> Result.Ok(parsed.parsed)
                SavedTagsParser.Result.NotAuthenticated -> Result.NotAuthenticated
            }
            FetchOutcome.NotFound ->
                // RR removed the filter page — treat as endpoint-
                // shape-changed: not a crash, just a skipped sync.
                Result.Error("Saved-tags read URL returned 404 — endpoint shape changed")
            is FetchOutcome.CloudflareChallenge ->
                Result.Error("Cloudflare challenge on saved-tags read")
            is FetchOutcome.RateLimited ->
                Result.Error("Rate limited on saved-tags read")
            is FetchOutcome.HttpError -> when (outcome.code) {
                401, 403 -> Result.NotAuthenticated
                else -> Result.Error("HTTP ${outcome.code}: ${outcome.message}")
            }
        }

    /**
     * Push the canonical [tags] set back to RR. Requires a fresh
     * [csrfToken] from a recent [fetchSavedTags] call — RR's
     * antiforgery tokens are per-session-per-page so a token
     * harvested mid-sync is the safest bet.
     *
     * Returns [Result.Ok] of Unit on success, [Result.NotAuthenticated]
     * when the POST is rejected for auth reasons, [Result.Error]
     * otherwise. The caller's policy on errors: skip-and-retry-at-
     * next-24h, never surface the failure to the user (issue #178
     * spec: "Network failures are retried at 24h next-sync; we
     * don't surface them.")
     */
    suspend fun pushSavedTags(tags: Set<String>, csrfToken: String): Result<Unit> {
        val body = FormBody.Builder().apply {
            add("globalFilters", "true")
            add("saveAsFilter", "true")
            for (tag in tags) add("tagsAdd", tag)
            add("__RequestVerificationToken", csrfToken)
        }.build()
        return runCatching {
            client.post(
                RoyalRoadTagSyncEndpoints.writeUrl,
                body,
                extraHeaders = mapOf("Referer" to RoyalRoadTagSyncEndpoints.writeReferer),
            ).use { resp ->
                when {
                    resp.code == 401 || resp.code == 403 -> Result.NotAuthenticated
                    resp.isSuccessful || resp.isRedirect -> Result.Ok(Unit)
                    // 4xx other than auth — endpoint shape changed
                    // (RR removed or renamed the save endpoint).
                    // Treat as transient so we retry next window.
                    resp.code in 400..499 -> Result.Error(
                        "Saved-tags write rejected: HTTP ${resp.code} — endpoint shape may have changed"
                    )
                    else -> Result.Error("Saved-tags write failed: HTTP ${resp.code}: ${resp.message}")
                }
            }
        }.getOrElse { Result.Error("Saved-tags write threw: ${it.message}") }
    }
}
