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
) : GoogleDriveConfig {

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

    private fun snapshot(): GoogleDriveConfigState = GoogleDriveConfigState(
        accessToken = secrets.getString(GDRIVE_ACCESS_TOKEN_PREF, "").orEmpty(),
        accountLabel = secrets.getString(GDRIVE_ACCOUNT_LABEL_PREF, "").orEmpty(),
    )

    /**
     * Persist a completed OAuth session. Keeps a prior refresh token when the
     * response omits one (Google returns a refresh token only on first
     * consent / with `prompt=consent`, never on a plain refresh).
     */
    fun saveOAuthSession(accessToken: String, refreshToken: String?, accountLabel: String = "") {
        secrets.edit()
            .putString(GDRIVE_ACCESS_TOKEN_PREF, accessToken.trim())
            .apply {
                if (!refreshToken.isNullOrBlank()) {
                    putString(GDRIVE_REFRESH_TOKEN_PREF, refreshToken.trim())
                }
                putString(GDRIVE_ACCOUNT_LABEL_PREF, accountLabel)
            }
            .apply()
        bump()
    }

    /** Rotate only the access token after a silent refresh (Google keeps the
     *  same refresh token; swap it only if a new one arrives). */
    fun updateOAuthTokens(accessToken: String, refreshToken: String?) {
        secrets.edit()
            .putString(GDRIVE_ACCESS_TOKEN_PREF, accessToken.trim())
            .apply {
                if (!refreshToken.isNullOrBlank()) {
                    putString(GDRIVE_REFRESH_TOKEN_PREF, refreshToken.trim())
                }
            }
            .apply()
        bump()
    }

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
            .remove(GDRIVE_OAUTH_STATE_PREF)
            .remove(GDRIVE_CODE_VERIFIER_PREF)
            .apply()
        bump()
    }

    private fun bump() { secretsTick.value = secretsTick.value + 1 }
}
