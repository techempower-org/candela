package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.matrix.config.MatrixConfig
import `in`.jphe.storyvox.source.matrix.config.MatrixConfigState
import `in`.jphe.storyvox.source.matrix.config.MatrixDefaults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.matrixDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_matrix")

private object MatrixKeys {
    /** Configured homeserver URL (e.g. `https://matrix.org`). User
     *  pastes this in Settings; empty means the source returns
     *  AuthRequired on every call. */
    val HOMESERVER_URL = stringPreferencesKey("pref_matrix_homeserver_url")
    /** Resolved `@user:homeserver` Matrix id, captured at the moment
     *  the access token validated via `whoami`. Drives the Settings
     *  "Signed in as @alice:matrix.org" confirmation row. Empty when
     *  the user hasn't validated yet; the source still works on a
     *  non-empty token + homeserver, this is a UX nicety. */
    val RESOLVED_USER_ID = stringPreferencesKey("pref_matrix_resolved_user_id")
    /** Same-sender coalesce window in minutes (slider range 1-30).
     *  Defaults to [MatrixDefaults.DEFAULT_COALESCE_MINUTES] when
     *  unset. Mirrors Discord's [DiscordKeys.COALESCE_MINUTES]. */
    val COALESCE_MINUTES = intPreferencesKey("pref_matrix_coalesce_minutes")
}

/** EncryptedSharedPreferences key for the Matrix access token. Lives
 *  next to the Discord / Telegram / Notion / Outline tokens in
 *  `storyvox.secrets`. The literal key string is the spec-provided
 *  one from issue #457, and is also registered in
 *  [`in`.jphe.storyvox.sync.domain.SecretsSyncer.Companion.SECRET_KEY_NAMES]
 *  so a configured Matrix backend syncs cross-device. */
internal const val MATRIX_ACCESS_TOKEN_PREF = "pref_source_matrix_token"

/**
 * Issue #457 — production [MatrixConfig]. Homeserver URL + resolved
 * user id + coalesce window in plaintext DataStore (no secrets),
 * access token in EncryptedSharedPreferences alongside the other
 * source tokens.
 *
 * Mirrors [DiscordConfigImpl] / [TelegramConfigImpl] /
 * [NotionConfigImpl] / [OutlineConfigImpl] — the parallel
 * structure keeps the secrets store one consistent surface across
 * every plugin that needs a token.
 *
 * Defaults: empty token + empty homeserver URL (no baked-in default
 * — Matrix is federated, picking matrix.org as the default would be
 * an opinionated nudge against self-hosted homeservers, which is the
 * audience this backend most directly serves). Coalesce window
 * defaults to [MatrixDefaults.DEFAULT_COALESCE_MINUTES] (5 min).
 *
 * **Not wired into [SettingsRepositoryUiImpl] in this PR**. The
 * Settings UI surface (token-entry sheet, homeserver URL field,
 * room-picker dropdown, coalesce-minutes slider) is a follow-up.
 * Wiring this through `SettingsRepositoryUiImpl`'s constructor
 * would ripple through ~5 test files and overlap with the parallel
 * Slack-backend agent (#454) editing the same file. The backend
 * ships functional via KSP-driven plugin registration; users can
 * exercise it via the existing Plugin Manager toggle once the
 * follow-up surfaces the configuration fields.
 */
@Singleton
class MatrixConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : MatrixConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.matrixDataStore, secrets)

    /**
     * Tick bumped whenever [setAccessToken] runs so the [state] flow
     * re-emits with the fresh token value. SharedPreferences doesn't
     * expose a Flow on its own — same pattern as `DiscordConfigImpl`
     * and `TelegramConfigImpl` use for their token legs.
     */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<MatrixConfigState> = combine(
        store.data.map { prefs ->
            Triple(
                prefs[MatrixKeys.HOMESERVER_URL].orEmpty(),
                prefs[MatrixKeys.RESOLVED_USER_ID].orEmpty(),
                prefs[MatrixKeys.COALESCE_MINUTES] ?: MatrixDefaults.DEFAULT_COALESCE_MINUTES,
            )
        }.distinctUntilChanged(),
        secretsTick,
    ) { (homeserverUrl, resolvedUserId, coalesce), _ ->
        val token = secrets.getString(MATRIX_ACCESS_TOKEN_PREF, "") ?: ""
        MatrixConfigState(
            accessToken = token,
            homeserverUrl = homeserverUrl,
            resolvedUserId = resolvedUserId,
            coalesceMinutes = coalesce.coerceIn(
                MatrixDefaults.MIN_COALESCE_MINUTES,
                MatrixDefaults.MAX_COALESCE_MINUTES,
            ),
        )
    }.distinctUntilChanged()

    override suspend fun current(): MatrixConfigState {
        val prefs = store.data.first()
        val token = secrets.getString(MATRIX_ACCESS_TOKEN_PREF, "") ?: ""
        val coalesce = (prefs[MatrixKeys.COALESCE_MINUTES] ?: MatrixDefaults.DEFAULT_COALESCE_MINUTES)
            .coerceIn(MatrixDefaults.MIN_COALESCE_MINUTES, MatrixDefaults.MAX_COALESCE_MINUTES)
        return MatrixConfigState(
            accessToken = token,
            homeserverUrl = prefs[MatrixKeys.HOMESERVER_URL].orEmpty(),
            resolvedUserId = prefs[MatrixKeys.RESOLVED_USER_ID].orEmpty(),
            coalesceMinutes = coalesce,
        )
    }

    /**
     * Persist the access token. Null/blank clears the store entry so
     * the source returns AuthRequired on subsequent calls. Bumps
     * [secretsTick] so the state flow re-emits without an explicit
     * Settings re-fetch.
     */
    fun setAccessToken(token: String?) {
        if (token.isNullOrBlank()) {
            secrets.edit().remove(MATRIX_ACCESS_TOKEN_PREF).apply()
        } else {
            secrets.edit().putString(MATRIX_ACCESS_TOKEN_PREF, token.trim()).apply()
        }
        secretsTick.value = secretsTick.value + 1
    }

    /** True when a non-empty access token is stored. Drives the
     *  UI's `matrixTokenConfigured: Boolean` projection when the
     *  Settings surface lands. */
    fun isTokenConfigured(): Boolean =
        !secrets.getString(MATRIX_ACCESS_TOKEN_PREF, "").isNullOrBlank()

    /**
     * Persist the homeserver URL. Normalises by trimming whitespace
     * + a trailing slash so concatenation with
     * `/_matrix/client/v3/...` always yields a clean URL. Empty
     * input wipes the stored value (homeserver picker goes back to
     * "no homeserver selected" state).
     */
    suspend fun setHomeserverUrl(url: String) {
        val normalised = url.trim().trimEnd('/')
        store.edit { prefs ->
            if (normalised.isBlank()) {
                prefs.remove(MatrixKeys.HOMESERVER_URL)
            } else {
                prefs[MatrixKeys.HOMESERVER_URL] = normalised
            }
        }
    }

    /**
     * Persist the resolved `@user:homeserver` Matrix id. Called
     * after a successful `whoami` so the Settings confirmation row
     * can display "Signed in as @alice:matrix.org" without an
     * extra round-trip on every reload. Empty input wipes the
     * stored value.
     */
    suspend fun setResolvedUserId(userId: String) {
        val trimmed = userId.trim()
        store.edit { prefs ->
            if (trimmed.isBlank()) {
                prefs.remove(MatrixKeys.RESOLVED_USER_ID)
            } else {
                prefs[MatrixKeys.RESOLVED_USER_ID] = trimmed
            }
        }
    }

    /** Persist the same-sender coalesce window (minutes). Clamps to
     *  the documented slider bounds before storing. */
    suspend fun setCoalesceMinutes(minutes: Int) {
        val safe = minutes.coerceIn(
            MatrixDefaults.MIN_COALESCE_MINUTES,
            MatrixDefaults.MAX_COALESCE_MINUTES,
        )
        store.edit { it[MatrixKeys.COALESCE_MINUTES] = safe }
    }

    /** Wipe homeserver URL, resolved user id, coalesce override,
     *  and access token — Settings "Forget Matrix" path (no UI
     *  affordance yet; available for diagnostics + tests). After
     *  this call the source falls back to AuthRequired on every
     *  fetch. */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(MatrixKeys.HOMESERVER_URL)
            prefs.remove(MatrixKeys.RESOLVED_USER_ID)
            prefs.remove(MatrixKeys.COALESCE_MINUTES)
        }
        secrets.edit().remove(MATRIX_ACCESS_TOKEN_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }
}
