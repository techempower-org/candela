package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf

private val Context.palaceDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_palace")

private object PalaceKeys {
    /** LAN host or hostname:port. Plaintext — it's a network address, not a secret.
     *  Empty value disables the source entirely. */
    val HOST = stringPreferencesKey("pref_palace_host")
    /** Issue #1468 — opt-in highlight → palace write-back. Absent = off. */
    val HIGHLIGHT_WRITE_BACK = booleanPreferencesKey("pref_palace_highlight_write_back")
}

/** EncryptedSharedPreferences key for the daemon API token. Lives next
 *  to the Royal Road cookie in the existing `storyvox.secrets` store. */
internal const val PALACE_API_KEY_PREF = "palace.api_key"

/**
 * Production [PalaceConfig] backed by a tiny dedicated DataStore (host)
 * + the existing encrypted SharedPreferences (api key). Kept separate
 * from [SettingsRepositoryUiImpl]'s `storyvox_settings` so the palace
 * field set can grow without churning that file's preference schema.
 */
@Singleton
class PalaceConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : PalaceConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.palaceDataStore, secrets)

    override val state: Flow<PalaceConfigState> = combine(
        store.data.map {
            it[PalaceKeys.HOST].orEmpty() to (it[PalaceKeys.HIGHLIGHT_WRITE_BACK] ?: false)
        }.distinctUntilChanged(),
        // SharedPreferences doesn't expose a native Flow. The api key is
        // edited via Settings on the main thread; rather than wire up a
        // listener, we re-read it on every host change. The api key
        // alone changing without the host changing is rare (user types
        // both at once, then taps Save) so this is good enough.
        flowOf(Unit),
    ) { (host, writeBack), _ ->
        PalaceConfigState(
            host = host,
            apiKey = secrets.getString(PALACE_API_KEY_PREF, "") ?: "",
            highlightWriteBack = writeBack,
        )
    }.distinctUntilChanged()

    override suspend fun current(): PalaceConfigState {
        val prefs = store.data.first()
        return PalaceConfigState(
            host = prefs[PalaceKeys.HOST].orEmpty(),
            apiKey = secrets.getString(PALACE_API_KEY_PREF, "") ?: "",
            highlightWriteBack = prefs[PalaceKeys.HIGHLIGHT_WRITE_BACK] ?: false,
        )
    }

    /**
     * Mutator hooks for Settings UI to persist the user's input. Kept
     * on the impl rather than the [PalaceConfig] interface because the
     * source module shouldn't be able to write to the config it reads.
     */
    suspend fun setHost(host: String) {
        store.edit { prefs -> prefs[PalaceKeys.HOST] = host.trim() }
    }

    fun setApiKey(apiKey: String) {
        secrets.edit().putString(PALACE_API_KEY_PREF, apiKey).apply()
    }

    /** Issue #1468 — opt in/out of highlight → palace write-back. */
    suspend fun setHighlightWriteBack(enabled: Boolean) {
        store.edit { prefs -> prefs[PalaceKeys.HIGHLIGHT_WRITE_BACK] = enabled }
    }

    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(PalaceKeys.HOST)
            prefs.remove(PalaceKeys.HIGHLIGHT_WRITE_BACK)
        }
        secrets.edit().remove(PALACE_API_KEY_PREF).apply()
    }
}
