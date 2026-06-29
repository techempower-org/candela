package `in`.jphe.storyvox.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1295 — gate for Google News full-article-text extraction.
 *
 * Resolving a story's real publisher URL goes through Google's internal
 * `batchexecute` RPC, which is fragile (breaks on Google format changes) and
 * ToS-gray (the feed restricts use to personal feed-reader rendering). So the
 * behaviour is **opt-in, default OFF**: when disabled, the Google News source
 * narrates its headline + related-coverage digest exactly as before.
 *
 * Implemented by the app's settings store (DataStore-backed) and consumed by
 * `:source-google-news`'s article resolver — same shape as [playback.AutoBrowserConfig].
 */
interface GoogleNewsConfig {

    /** Live flow of the opt-in flag. */
    val fullArticleTextEnabled: Flow<Boolean>

    /** Snapshot read; false until the store emits (safe default — never
     *  performs the ToS-gray decode unless the user explicitly opted in). */
    suspend fun isFullArticleTextEnabled(): Boolean
}
