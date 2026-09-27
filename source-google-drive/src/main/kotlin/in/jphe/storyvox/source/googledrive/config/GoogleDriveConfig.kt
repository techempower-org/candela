package `in`.jphe.storyvox.source.googledrive.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1496 — abstraction over the Google Drive source's persistent
 * session. The source module stays free of Android Preferences plumbing so
 * the leaf-source architecture (source modules don't depend on `:app`)
 * holds — the production implementation
 * (`in.jphe.storyvox.data.GoogleDriveConfigImpl`) lives in `:app` on top of
 * the shared `storyvox.secrets` EncryptedSharedPreferences, exactly like
 * [`in`.jphe.storyvox.source.outline.config.OutlineConfig] and
 * [`in`.jphe.storyvox.source.notion.config.NotionConfig].
 *
 * Google Drive has **no anonymous read path** (unlike Notion's public
 * pages): with the load-bearing `drive.file` scope, the app can only see
 * files the user has explicitly granted through the Google OAuth flow +
 * Google Picker. So the whole source is gated on a non-blank
 * [GoogleDriveConfigState.accessToken] — blank means "not connected" and
 * every call short-circuits to `AuthRequired`.
 */
interface GoogleDriveConfig {
    /** Hot stream of the current session state. */
    val state: Flow<GoogleDriveConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): GoogleDriveConfigState

    /**
     * #1677 — the access token to send *now*. The production impl refreshes
     * proactively when the stored token is at/near its expiry and a refresh
     * token exists; blank means "not connected". Default: the stored token
     * as-is (test fakes and any impl without refresh support).
     */
    suspend fun freshAccessToken(): String = current().accessToken

    /**
     * #1677 — force a refresh after the API answered 401/403 with the current
     * token. Returns the new access token, or null when no refresh is possible
     * (no refresh token, revoked grant, network down) — the source then
     * surfaces `AuthRequired` so the UI offers Connect again. Default: null.
     */
    suspend fun refreshAccessToken(): String? = null
}

/**
 * Immutable snapshot of the Drive session.
 *
 * @property accessToken the OAuth 2.0 access token (`drive.file` scope).
 *  Blank when not connected. Sent as `Authorization: Bearer <token>`.
 * @property accountLabel display-only "connected as" label (the Google
 *  account email / name, when the token response carried one). Never a
 *  secret; empty when unknown.
 */
data class GoogleDriveConfigState(
    val accessToken: String = "",
    val accountLabel: String = "",
) {
    /** True once an OAuth access token is present. Drives the source's
     *  AuthRequired short-circuit and the UI's "connected" projection. */
    val connected: Boolean get() = accessToken.isNotBlank()
}
