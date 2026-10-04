package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.telegram.config.TelegramConfig
import `in`.jphe.storyvox.source.telegram.config.TelegramConfigState
import `in`.jphe.storyvox.source.telegram.config.TelegramDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Issue #462 — DataStore exists for parity with the other backend
 * configs even though Telegram has no plaintext prefs in v1. The
 * empty store is a no-op carrier; when a future PR adds e.g. a
 * persisted `lastUpdateId` so observed-posts survive process
 * restarts, the store is already wired in.
 */
private val Context.telegramDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_telegram",
)

/** EncryptedSharedPreferences key for the Telegram bot token.
 *  Lives next to the Discord token in `storyvox.secrets`.
 *
 *  **Sync posture (v1)**: NOT registered in
 *  [SecretsSyncer.SECRET_KEY_NAMES] so the token stays device-
 *  local. A future PR can add it (one-line addition) to sync via
 *  InstantDB; deliberately omitted in this PR to avoid colliding
 *  with the parallel accessibility-settings agent's edits to the
 *  same allowlist. */
internal const val TELEGRAM_BOT_TOKEN_PREF = "pref_source_telegram_token"

/**
 * Issue #462 — production [TelegramConfig]. Bot token in
 * EncryptedSharedPreferences alongside the other source tokens
 * (Discord / Notion / Outline). No plaintext prefs in v1 — the
 * channel list is derived from observed `getUpdates` activity at
 * runtime, not user-configured.
 *
 * Mirrors [DiscordConfigImpl] but trims the server-id / coalesce
 * legs since Telegram doesn't need either:
 *  - No server picker (channels are auto-discovered via getUpdates)
 *  - No message coalescing (each channel_post is one chapter;
 *    channel posts are admin-curated and rarely need coalescing
 *    the way Discord chat does)
 */
@Singleton
class TelegramConfigImpl(
    @Suppress("unused") private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : TelegramConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.telegramDataStore, secrets)

    /**
     * Tick bumped whenever [setApiToken] runs so the [state] flow
     * re-emits with the fresh token value. SharedPreferences doesn't
     * expose a Flow on its own — same pattern as
     * [DiscordConfigImpl] / [OutlineConfigImpl] / [NotionConfigImpl]
     * use for their token legs.
     */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<TelegramConfigState> = combine(
        flowOf(Unit), // placeholder for future plaintext prefs
        secretsTick,
    ) { _, _ ->
        val token = secrets.getString(TELEGRAM_BOT_TOKEN_PREF, "") ?: ""
        TelegramConfigState(
            apiToken = token,
            baseUrl = TelegramDefaults.BASE_URL,
        )
    }.distinctUntilChanged()

    override suspend fun current(): TelegramConfigState {
        val token = secrets.getString(TELEGRAM_BOT_TOKEN_PREF, "") ?: ""
        return TelegramConfigState(
            apiToken = token,
            baseUrl = TelegramDefaults.BASE_URL,
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
            secrets.edit().remove(TELEGRAM_BOT_TOKEN_PREF).apply()
        } else {
            secrets.edit().putString(TELEGRAM_BOT_TOKEN_PREF, token.trim()).apply()
        }
        secretsTick.value = secretsTick.value + 1
    }

    /** True when a non-empty bot token is stored. Drives the UI's
     *  `telegramTokenConfigured: Boolean` projection. */
    fun isTokenConfigured(): Boolean =
        !secrets.getString(TELEGRAM_BOT_TOKEN_PREF, "").isNullOrBlank()

    /** Wipe the token — Settings "Forget Telegram" path (no UI
     *  affordance yet; available for diagnostics + tests). */
    fun clear() {
        secrets.edit().remove(TELEGRAM_BOT_TOKEN_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }
}
