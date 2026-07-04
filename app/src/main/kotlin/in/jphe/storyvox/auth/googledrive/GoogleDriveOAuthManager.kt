package `in`.jphe.storyvox.auth.googledrive

import android.util.Log
import dagger.Lazy
import `in`.jphe.storyvox.data.GoogleDriveConfigImpl
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1496 — orchestrates the Google Drive OAuth flow end to end:
 *
 *  - [beginConnect] generates the PKCE verifier + CSRF `state` nonce,
 *    persists both (they must survive process death — a Custom Tab can evict
 *    the app), and returns the authorize URL for the sheet to open.
 *  - [handleCallback] verifies the `state`, exchanges the code (with the
 *    persisted `code_verifier`), persists the session, and auto-enables the
 *    `google-drive` source so its Browse chip lights up.
 *
 * Depends on `Lazy<SettingsRepositoryUi>` only to flip the plugin-enabled
 * pref on success; nothing on the settings chain depends back on it, so
 * there's no Dagger cycle (the Lazy edge is belt-and-suspenders, mirroring
 * [`in`.jphe.storyvox.auth.notion.NotionOAuthManager]).
 *
 * Used by [`in`.jphe.storyvox.MainActivity]'s `onNewIntent` handler.
 */
@Singleton
class GoogleDriveOAuthManager @Inject constructor(
    private val api: GoogleDriveOAuthApi,
    private val config: GoogleDriveConfigImpl,
    private val settings: Lazy<SettingsRepositoryUi>,
) {
    /** Result of handling a redirect, for user-facing feedback. */
    sealed class Outcome {
        /** Connected. [accountLabel] is blank when Google returned none
         *  (drive.file-only grants carry no email). */
        data class Connected(val accountLabel: String) : Outcome()

        /** User declined consent (`error=access_denied`) — no-op, no crash. */
        data object Cancelled : Outcome()

        /** Redirect didn't verify (missing/mismatched state, missing
         *  verifier, or no code) — a likely CSRF or stale redirect. */
        data object InvalidCallback : Outcome()

        /** The token exchange itself failed. */
        data class ExchangeFailed(val message: String) : Outcome()
    }

    /**
     * Start a connect flow. Returns the authorize URL to open in a Chrome
     * Custom Tab, or null when this build has no OAuth client id (the
     * Connect button should be hidden in that case).
     */
    fun beginConnect(): String? {
        if (!GoogleDriveOAuthConfig.isAvailable) return null
        val state = UUID.randomUUID().toString()
        val verifier = GoogleDrivePkce.newCodeVerifier()
        config.setOAuthState(state)
        config.setCodeVerifier(verifier)
        return GoogleDriveOAuthConfig.authorizeUrl(
            state = state,
            codeChallenge = GoogleDrivePkce.challengeFor(verifier),
        )
    }

    /**
     * Handle a `candela://oauth/googledrive` redirect. Always clears the
     * single-use nonce + verifier first so a replayed redirect can't reuse
     * them.
     */
    suspend fun handleCallback(code: String?, state: String?, error: String?): Outcome {
        val expectedState = config.oauthState()
        val verifier = config.codeVerifier()
        config.clearOAuthTransient()

        if (error != null) {
            Log.i(TAG, "Google Drive OAuth declined: $error")
            return Outcome.Cancelled
        }
        if (code.isNullOrBlank() || state.isNullOrBlank() ||
            expectedState.isNullOrBlank() || state != expectedState ||
            verifier.isNullOrBlank()
        ) {
            Log.w(TAG, "Google Drive OAuth callback failed state/verifier verification")
            return Outcome.InvalidCallback
        }

        return when (val r = api.exchangeCode(code = code, codeVerifier = verifier)) {
            is GoogleDriveOAuthResult.Success -> {
                config.saveOAuthSession(
                    accessToken = r.accessToken,
                    refreshToken = r.refreshToken,
                )
                runCatching { settings.get().setSourcePluginEnabled(SOURCE_ID, true) }
                    .onFailure { Log.w(TAG, "auto-enable google-drive failed", it) }
                Outcome.Connected(config.current().accountLabel)
            }
            is GoogleDriveOAuthResult.Failure -> {
                Log.w(TAG, "Google Drive token exchange failed: ${r.code} ${r.message}")
                Outcome.ExchangeFailed(r.message)
            }
        }
    }

    private companion object {
        const val TAG = "GoogleDriveOAuth"
        /** The @SourcePlugin id of :source-google-drive (source of truth;
         *  no SourceIds constant). */
        const val SOURCE_ID = "google-drive"
    }
}
