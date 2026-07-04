package `in`.jphe.storyvox.auth.notion

import android.util.Log
import dagger.Lazy
import `in`.jphe.storyvox.data.NotionConfigImpl
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1507 — orchestrates the Notion OAuth callback: verify the CSRF
 * `state` nonce, exchange the code for tokens, persist the session, and
 * auto-enable the Notion (PAT) source so its Browse chip lights up with
 * granted content.
 *
 * The **launch** side (build the authorize URL + persist the nonce) lives
 * in `SettingsRepositoryUiImpl.beginNotionOAuth()` so this manager stays
 * off the settings-repo dependency chain — it depends on
 * `Lazy<SettingsRepositoryUi>` (only to flip the plugin-enabled pref on
 * success), and nothing on the settings chain depends back on it, so there
 * is no Dagger cycle (the Lazy edge is belt-and-suspenders).
 *
 * Used by [`in`.jphe.storyvox.MainActivity]'s `onNewIntent` handler.
 */
@Singleton
class NotionOAuthManager @Inject constructor(
    private val api: NotionOAuthApi,
    private val config: NotionConfigImpl,
    private val settings: Lazy<SettingsRepositoryUi>,
) {
    /** Result of handling a redirect, for user-facing feedback. */
    sealed class Outcome {
        /** Connected; [workspaceName] is blank if Notion didn't return one. */
        data class Connected(val workspaceName: String) : Outcome()

        /** User declined consent (`error=access_denied`) — no-op, no crash. */
        data object Cancelled : Outcome()

        /** Redirect didn't verify (missing/mismatched state or code) — a
         *  likely CSRF or a stale/duplicate redirect; ignored safely. */
        data object InvalidCallback : Outcome()

        /** The token exchange itself failed. */
        data class ExchangeFailed(val message: String) : Outcome()
    }

    /**
     * Handle a `candela://oauth/notion` redirect. Always clears the
     * single-use nonce first so a replayed redirect can't be reused.
     */
    suspend fun handleCallback(code: String?, state: String?, error: String?): Outcome {
        val expected = config.oauthState()
        config.clearOAuthState()

        if (error != null) {
            Log.i(TAG, "Notion OAuth declined: $error")
            return Outcome.Cancelled
        }
        if (code.isNullOrBlank() || state.isNullOrBlank() || expected.isNullOrBlank() || state != expected) {
            Log.w(TAG, "Notion OAuth callback failed state verification")
            return Outcome.InvalidCallback
        }

        return when (val r = api.exchangeCode(code)) {
            is NotionOAuthResult.Success -> {
                config.saveOAuthSession(
                    accessToken = r.accessToken,
                    refreshToken = r.refreshToken,
                    workspaceName = r.workspaceName,
                )
                // Auto-enable the Notion (PAT) source so the connected
                // workspace's content shows in Browse without a manual
                // Settings → Plugins toggle.
                runCatching { settings.get().setSourcePluginEnabled(SourceIds.NOTION_PAT, true) }
                    .onFailure { Log.w(TAG, "auto-enable notion-pat failed", it) }
                Outcome.Connected(r.workspaceName.orEmpty())
            }
            is NotionOAuthResult.Failure -> {
                Log.w(TAG, "Notion token exchange failed: ${r.code} ${r.message}")
                Outcome.ExchangeFailed(r.message)
            }
        }
    }

    private companion object {
        const val TAG = "NotionOAuth"
    }
}
