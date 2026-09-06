package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.endless.config.EndlessConfig
import `in`.jphe.storyvox.source.endless.config.EndlessConfigState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.endlessDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "storyvox_endless")

private object EndlessKeys {
    /** endless-litrpg daemon host, `host[:port]`, optional scheme.
     *  Plaintext — a LAN network address, not a secret (the daemon has
     *  no auth at all). Absent/blank = not configured. */
    val HOST = stringPreferencesKey("pref_endless_host")
}

/**
 * Production [EndlessConfig], backed by its own small DataStore.
 *
 * Same one-store-per-source posture as [PalaceLibraryConfigImpl] /
 * [OutlineConfigImpl]: `:source-endless` stays DataStore-free and only
 * READS through the [EndlessConfig] contract, while the write side
 * ([setHost]) lives here so the source cannot mutate the config it
 * consumes. Bound in `AppBindings`; surfaced in Settings through
 * [EndlessConfigContributor].
 *
 * Host-only — the daemon has no credentials, so nothing here needs
 * EncryptedSharedPreferences (contrast [PalaceConfigImpl], which stores
 * an API key).
 *
 * **No default host.** The daemon is JP's own LAN service and its address
 * moves between machines; a compiled-in default would be wrong for
 * everyone else and would fail as a connect timeout rather than as a
 * legible "not configured yet".
 */
@Singleton
class EndlessConfigImpl(
    private val store: DataStore<Preferences>,
) : EndlessConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(context.endlessDataStore)

    // DataStore replays the latest value on subscribe, satisfying the
    // EndlessConfig "emit current value on subscribe" contract.
    override val state: Flow<EndlessConfigState> = store.data
        .map { EndlessConfigState(host = it[EndlessKeys.HOST].orEmpty()) }
        .distinctUntilChanged()

    override suspend fun current(): EndlessConfigState =
        EndlessConfigState(host = store.data.first()[EndlessKeys.HOST].orEmpty())

    /** Persist the daemon host. Blank clears it. */
    suspend fun setHost(host: String?) {
        val trimmed = host?.trim().orEmpty()
        store.edit { prefs ->
            if (trimmed.isBlank()) prefs.remove(EndlessKeys.HOST)
            else prefs[EndlessKeys.HOST] = trimmed
        }
    }
}
