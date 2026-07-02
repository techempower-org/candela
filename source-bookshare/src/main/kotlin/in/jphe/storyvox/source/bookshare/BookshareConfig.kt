package `in`.jphe.storyvox.source.bookshare

/**
 * Issue #1002 — runtime credentials for the Bookshare API.
 *
 * Bookshare API v2 requires a partner `api_key` on every endpoint (issued by
 * partner-support@bookshare.org) and, for member-scoped endpoints, a per-user
 * OAuth bearer token. Neither is hardcodable — they're partnership / user data
 * — so [BookshareSource] reads them through this seam.
 *
 * The production implementation is `:app`'s `BookshareConfigImpl` (#1471),
 * `@Provides`-bound over this interface and backed by the encrypted
 * `storyvox.secrets` bag. Until the partner key is entered in Settings,
 * [apiKey] returns null and every Bookshare network call short-circuits to
 * `FictionResult.AuthRequired`. Mirrors the `DiscordConfig` / `NotionConfig`
 * source-credential seams.
 */
interface BookshareConfig {

    /** Partner API key (request param `api_key`), or null when unconfigured. */
    suspend fun apiKey(): String?

    /** Per-user OAuth bearer token for member-scoped endpoints, or null. */
    suspend fun accessToken(): String?
}
