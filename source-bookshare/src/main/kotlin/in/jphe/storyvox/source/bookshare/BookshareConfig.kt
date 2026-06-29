package `in`.jphe.storyvox.source.bookshare

import javax.inject.Inject

/**
 * Issue #1002 — runtime credentials for the Bookshare API.
 *
 * Bookshare API v2 requires a partner `api_key` on every endpoint (issued by
 * partner-support@bookshare.org) and, for member-scoped endpoints, a per-user
 * OAuth bearer token. Neither is hardcodable — they're partnership / user data
 * — so [BookshareSource] reads them through this seam. The in-memory default
 * ([InMemoryBookshareConfig]) returns null for both, so until a DataStore-backed
 * implementation in :app supplies them, every Bookshare network call
 * short-circuits to `FictionResult.AuthRequired`. Mirrors the
 * `PalaceLibraryConfig` / `GoogleNewsConfig` config seams.
 */
interface BookshareConfig {

    /** Partner API key (request param `api_key`), or null when unconfigured. */
    suspend fun apiKey(): String?

    /** Per-user OAuth bearer token for member-scoped endpoints, or null. */
    suspend fun accessToken(): String?
}

/**
 * Default no-credentials binding. Replaced by a DataStore-backed implementation
 * in :app once the Bookshare partnership + settings UI land (tracked on #1002);
 * until then the source stays gated.
 */
internal class InMemoryBookshareConfig @Inject constructor() : BookshareConfig {
    override suspend fun apiKey(): String? = null
    override suspend fun accessToken(): String? = null
}
