package `in`.jphe.storyvox.data

import android.content.SharedPreferences
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfig
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfigState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** EncryptedSharedPreferences key for the Google Drive OAuth access token.
 *  Lives next to the Notion / Outline / palace tokens in `storyvox.secrets`. */
internal const val GDRIVE_ACCESS_TOKEN_PREF = "googledrive.access_token"

/** Issue #1496 — OAuth refresh token. Google does NOT rotate it on refresh,
 *  so it's kept until the session is cleared. Enables a future silent
 *  refresh-on-401 slice. */
internal const val GDRIVE_REFRESH_TOKEN_PREF = "googledrive.refresh_token"

/** Display-only "connected as" label from the token flow. Empty for
 *  drive.file-only grants (no email is returned). */
internal const val GDRIVE_ACCOUNT_LABEL_PREF = "googledrive.account_label"

/** #1677 — wall-clock ms at which the stored access token expires (0 when
 *  unknown). Drives the proactive refresh in [GoogleDriveConfigImpl.freshAccessToken]. */
internal const val GDRIVE_EXPIRES_AT_PREF = "googledrive.expires_at_ms"

/** CSRF `state` nonce persisted before the Custom Tab launches, verified on
 *  return. Single-use; survives process death. */
internal const val GDRIVE_OAUTH_STATE_PREF = "googledrive.oauth_state"

/** PKCE `code_verifier` persisted alongside the state nonce; sent back on the
 *  token exchange. Single-use; survives process death. */
internal const val GDRIVE_CODE_VERIFIER_PREF = "googledrive.code_verifier"

/**
 * Issue #1496 — production [GoogleDriveConfig]. Google Drive has no
 * plaintext-config leg (no host, no database id) — the whole session is the
 * OAuth token pair — so this lives purely on the shared `storyvox.secrets`
 * EncryptedSharedPreferences, with a [MutableStateFlow] tick to re-emit
 * [state] on writes (SharedPreferences exposes no Flow). Same secrets store
 * and pattern as [OutlineConfigImpl] / [NotionConfigImpl].
 */
@Singleton
class GoogleDriveConfigImpl @Inject constructor(
    private val secrets: SharedPreferences,
    // #1677 — token refresh. GoogleDriveOAuthApi has no deps of its own, so
    // this adds no cycle (the OAuth manager depends on THIS class, not back).
    private val oauthApi: `in`.jphe.storyvox.auth.googledrive.GoogleDriveOAuthApi,
) : GoogleDriveConfig {

    /** Serialises refreshes so N concurrent 401s spend one refresh call. */
    private val refreshLock = kotlinx.coroutines.sync.Mutex()

    /** Test seam for the expiry clock. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    /** Bumped on every write so [state] re-emits with fresh values. */
    private val secretsTick = MutableStateFlow(0L)

    // #1588 — snapshot() reads EncryptedSharedPreferences (synchronous Tink
    // crypto; the first touch after process start pays a 50-200ms keyset-init
    // cost). flowOn(IO) keeps that decrypt off whatever thread collects `state`
    // (SourceConfigContributors, the Settings UI), never the main thread.
    override val state: Flow<GoogleDriveConfigState> =
        secretsTick.map { snapshot() }.flowOn(Dispatchers.IO).distinctUntilChanged()

    // #1588 — same encrypted read as `state`; both GoogleDriveSource.token()
    // and the OAuth manager await this, so pin the decrypt to IO.
    override suspend fun current(): GoogleDriveConfigState =
        withContext(Dispatchers.IO) { snapshot() }

    /**
     * #1677 — refresh proactively when the stored token is within
     * [EXPIRY_SKEW_MS] of expiry (and a refresh token exists); otherwise the
     * stored token. A failed proactive refresh still returns the old token —
     * the source's 401 path then gets its own chance.
     */
    override suspend fun freshAccessToken(): String = withContext(Dispatchers.IO) {
        val token = secrets.getString(GDRIVE_ACCESS_TOKEN_PREF, "").orEmpty()
        if (token.isBlank()) return@withContext ""
        val expiresAt = secrets.getLong(GDRIVE_EXPIRES_AT_PREF, 0L)
        if (expiresAt > 0L && nowMs() >= expiresAt - EXPIRY_SKEW_MS && refreshTokenValue() != null) {
            refreshLocked(staleToken = token) ?: token
        } else {
            token
        }
    }

    /** #1677 — forced refresh after a 401/403 with the current token. */
    override suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        val token = secrets.getString(GDRIVE_ACCESS_TOKEN_PREF, "").orEmpty()
        if (token.isBlank()) return@withContext null
        refreshLocked(staleToken = token)
    }

    /**
     * Refresh under [refreshLock]. If another caller already rotated the
     * token while we waited ([staleToken] no longer current), reuse theirs.
     * An `invalid_grant` (revoked / expired refresh token) clears the session
     * so the UI falls back to Connect instead of retrying forever.
     */
    private suspend fun refreshLocked(staleToken: String): String? = refreshLock.withLock {
        val current = secrets.getString(GDRIVE_ACCESS_TOKEN_PREF, "").orEmpty()
        if (current.isNotBlank() && current != staleToken) return@withLock current
        val refresh = refreshTokenValue() ?: return@withLock null
        when (val r = oauthApi.refresh(refresh)) {
            is `in`.jphe.storyvox.auth.googledrive.GoogleDriveOAuthResult.Success -> {
                updateOAuthTokens(r.accessToken, r.refreshToken, r.expiresInSeconds)
                r.accessToken
            }
            is `in`.jphe.storyvox.auth.googledrive.GoogleDriveOAuthResult.Failure -> {
                if (r.code == "invalid_grant") clear()
                null
            }
        }
    }

    private fun snapshot(): GoogleDriveConfigState = GoogleDriveConfigState(
        accessToken = secrets.getString(GDRIVE_ACCESS_TOKEN_PREF, "").orEmpty(),
        accountLabel = secrets.getString(GDRIVE_ACCOUNT_LABEL_PREF, "").orEmpty(),
    )

    /**
     * Persist a completed OAuth session. Keeps a prior refresh token when the
     * response omits one (Google returns a refresh token only on first
     * consent / with `prompt=consent`, never on a plain refresh).
     */
    fun saveOAuthSession(
        accessToken: String,
        refreshToken: String?,
        accountLabel: String = "",
        expiresInSeconds: Long? = null,
    ) {
        secrets.edit()
            .putString(GDRIVE_ACCESS_TOKEN_PREF, accessToken.trim())
            .apply {
                if (!refreshToken.isNullOrBlank()) {
                    putString(GDRIVE_REFRESH_TOKEN_PREF, refreshToken.trim())
                }
                putString(GDRIVE_ACCOUNT_LABEL_PREF, accountLabel)
                putLong(GDRIVE_EXPIRES_AT_PREF, expiresAtMs(expiresInSeconds))
            }
            .apply()
        bump()
    }

    /** Rotate only the access token after a silent refresh (Google keeps the
     *  same refresh token; swap it only if a new one arrives). */
    fun updateOAuthTokens(accessToken: String, refreshToken: String?, expiresInSeconds: Long? = null) {
        secrets.edit()
            .putString(GDRIVE_ACCESS_TOKEN_PREF, accessToken.trim())
            .apply {
                if (!refreshToken.isNullOrBlank()) {
                    putString(GDRIVE_REFRESH_TOKEN_PREF, refreshToken.trim())
                }
                putLong(GDRIVE_EXPIRES_AT_PREF, expiresAtMs(expiresInSeconds))
            }
            .apply()
        bump()
    }

    /** 0 (unknown) when Google omitted `expires_in`. */
    private fun expiresAtMs(expiresInSeconds: Long?): Long =
        if (expiresInSeconds == null || expiresInSeconds <= 0L) 0L
        else nowMs() + expiresInSeconds * 1000L

    /** The stored refresh token, or null if none. */
    fun refreshTokenValue(): String? =
        secrets.getString(GDRIVE_REFRESH_TOKEN_PREF, null)?.ifBlank { null }

    /** True when a non-empty access token is stored. */
    fun isConnected(): Boolean =
        !secrets.getString(GDRIVE_ACCESS_TOKEN_PREF, "").isNullOrBlank()

    // ── OAuth transient (state nonce + PKCE verifier) ────────────────────

    fun setOAuthState(state: String) {
        secrets.edit().putString(GDRIVE_OAUTH_STATE_PREF, state).apply()
    }

    fun oauthState(): String? =
        secrets.getString(GDRIVE_OAUTH_STATE_PREF, null)?.ifBlank { null }

    fun setCodeVerifier(verifier: String) {
        secrets.edit().putString(GDRIVE_CODE_VERIFIER_PREF, verifier).apply()
    }

    fun codeVerifier(): String? =
        secrets.getString(GDRIVE_CODE_VERIFIER_PREF, null)?.ifBlank { null }

    /** Drop the single-use state nonce + PKCE verifier once the redirect is
     *  handled (or a flow is abandoned). */
    fun clearOAuthTransient() {
        secrets.edit()
            .remove(GDRIVE_OAUTH_STATE_PREF)
            .remove(GDRIVE_CODE_VERIFIER_PREF)
            .apply()
    }

    /** Wipe the full session — "Disconnect Google Drive". After this the
     *  source returns AuthRequired again. */
    fun clear() {
        secrets.edit()
            .remove(GDRIVE_ACCESS_TOKEN_PREF)
            .remove(GDRIVE_REFRESH_TOKEN_PREF)
            .remove(GDRIVE_ACCOUNT_LABEL_PREF)
            .remove(GDRIVE_EXPIRES_AT_PREF)
            .remove(GDRIVE_OAUTH_STATE_PREF)
            .remove(GDRIVE_CODE_VERIFIER_PREF)
            .apply()
        bump()
    }

    private fun bump() { secretsTick.value = secretsTick.value + 1 }

    private companion object {
        /** Refresh this long before the stated expiry (clock skew + a
         *  long chapter download starting just before the hour). */
        const val EXPIRY_SKEW_MS = 2 * 60 * 1000L
    }
}
