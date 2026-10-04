package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.slack.config.SlackConfig
import `in`.jphe.storyvox.source.slack.config.SlackConfigState
import `in`.jphe.storyvox.source.slack.config.SlackDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Issue #454 — DataStore for the small slice of Slack config that
 * isn't secret. Mirrors the Discord shape: plaintext bits in
 * DataStore (workspace name + url, captured at `auth.test` time for
 * empty-state copy), the bot token in EncryptedSharedPreferences.
 */
private val Context.slackDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_slack",
)

private object SlackKeys {
    /** Cached workspace name from the last successful `auth.test`,
     *  so the Settings card can render "Browsing TechEmpower"
     *  without an extra round-trip. Empty until the user authenticates. */
    val WORKSPACE_NAME = stringPreferencesKey("pref_slack_workspace_name")

    /** Cached workspace URL (e.g. `https://techempower.slack.com/`)
     *  from the last successful `auth.test`. */
    val WORKSPACE_URL = stringPreferencesKey("pref_slack_workspace_url")
}

/** EncryptedSharedPreferences key for the Slack bot token. Lives
 *  next to the Discord / Telegram tokens in `storyvox.secrets`.
 *  The literal key string is the spec-provided one from issue
 *  #454; matches the entry in
 *  [`in`.jphe.storyvox.sync.domain.SecretsSyncer.SECRET_KEY_NAMES]
 *  so the token syncs cross-device through InstantDB. */
internal const val SLACK_BOT_TOKEN_PREF = "pref_source_slack_token"

/**
 * Issue #454 — production [SlackConfig]. Workspace name/url in
 * plaintext DataStore (cached labels, no secrets), bot token in
 * EncryptedSharedPreferences alongside the other source tokens.
 *
 * Mirrors [DiscordConfigImpl] and [TelegramConfigImpl] — the
 * parallel structure keeps the secrets store one consistent surface
 * across `:source-discord`, `:source-telegram`, `:source-slack`,
 * `:source-notion`, and `:source-outline`.
 *
 * Defaults: empty token + empty workspace metadata (no baked-in
 * default — any default would imply storyvox auto-knows your
 * workspace, which doesn't fit the ToS posture).
 */
@Singleton
class SlackConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : SlackConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.slackDataStore, secrets)

    /**
     * Tick bumped whenever [setApiToken] runs so the [state] flow
     * re-emits with the fresh token value. SharedPreferences doesn't
     * expose a Flow on its own — same pattern as `DiscordConfigImpl`
     * / `TelegramConfigImpl` / `OutlineConfigImpl` use for their
     * token legs.
     */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<SlackConfigState> = combine(
        store.data.map { prefs ->
            (prefs[SlackKeys.WORKSPACE_NAME].orEmpty()) to
                (prefs[SlackKeys.WORKSPACE_URL].orEmpty())
        }.distinctUntilChanged(),
        secretsTick,
    ) { (name, url), _ ->
        val token = secrets.getString(SLACK_BOT_TOKEN_PREF, "") ?: ""
        SlackConfigState(
            apiToken = token,
            workspaceName = name,
            workspaceUrl = url,
            baseUrl = SlackDefaults.BASE_URL,
        )
    }.distinctUntilChanged()

    override suspend fun current(): SlackConfigState {
        val prefs = store.data.first()
        val token = secrets.getString(SLACK_BOT_TOKEN_PREF, "") ?: ""
        return SlackConfigState(
            apiToken = token,
            workspaceName = prefs[SlackKeys.WORKSPACE_NAME].orEmpty(),
            workspaceUrl = prefs[SlackKeys.WORKSPACE_URL].orEmpty(),
            baseUrl = SlackDefaults.BASE_URL,
        )
    }

    /**
     * Persist the bot token. Null/blank clears the store entry so
     * the source returns AuthRequired on subsequent calls. Bumps
     * [secretsTick] so the state flow re-emits without an explicit
     * Settings re-fetch.
     */
    fun setApiToken(token: String?) {
        if (token.isNullOrBlank()) {
            secrets.edit().remove(SLACK_BOT_TOKEN_PREF).apply()
        } else {
            secrets.edit().putString(SLACK_BOT_TOKEN_PREF, token.trim()).apply()
        }
        secretsTick.value = secretsTick.value + 1
    }

    /** True when a non-empty bot token is stored. Drives the UI's
     *  `slackTokenConfigured: Boolean` projection. */
    fun isTokenConfigured(): Boolean =
        !secrets.getString(SLACK_BOT_TOKEN_PREF, "").isNullOrBlank()

    /**
     * Persist the workspace metadata captured at `auth.test` time
     * so the Settings card can render the friendly name without an
     * extra round-trip on the next open. Empty input wipes the
     * stored values (Settings "Forget Slack" path).
     */
    suspend fun setWorkspace(name: String, url: String) {
        val trimmedName = name.trim()
        val trimmedUrl = url.trim()
        store.edit { prefs ->
            if (trimmedName.isBlank() && trimmedUrl.isBlank()) {
                prefs.remove(SlackKeys.WORKSPACE_NAME)
                prefs.remove(SlackKeys.WORKSPACE_URL)
            } else {
                prefs[SlackKeys.WORKSPACE_NAME] = trimmedName
                prefs[SlackKeys.WORKSPACE_URL] = trimmedUrl
            }
        }
    }

    /** Wipe workspace metadata + token — Settings "Forget Slack"
     *  path (no UI affordance yet; available for diagnostics +
     *  tests). After this call the source falls back to
     *  AuthRequired on every fetch. */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(SlackKeys.WORKSPACE_NAME)
            prefs.remove(SlackKeys.WORKSPACE_URL)
        }
        secrets.edit().remove(SLACK_BOT_TOKEN_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }
}
