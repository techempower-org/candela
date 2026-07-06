package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.palace.PalaceLibraryConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.palaceLibraryDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "storyvox_palace_library")

private object PalaceLibraryKeys {
    /** Palace Project OPDS library root URL. Plaintext — a network
     *  address, not a secret. Absent/blank = no library configured. */
    val ROOT_URL = stringPreferencesKey("pref_palace_library_root_url")
}

/**
 * Issue #1591 / #501 — production [PalaceLibraryConfig] backed by a tiny
 * dedicated DataStore, replacing source-palace's `InMemoryPalaceLibraryConfig`
 * fallback (which always emitted `null`).
 *
 * Same one-store-per-source posture as [OutlineConfigImpl] / [RadioConfigImpl]:
 * `:source-palace` stays DataStore-free and only READS through the
 * [PalaceLibraryConfig] contract, while the write side ([setRootUrl]) lives on
 * this impl so the source can't mutate the config it consumes. Bound in
 * `AppBindings` (`:app` is the sole provider, mirroring `RadioConfig`).
 *
 * URL-only — Palace v1 reads public OPDS feeds, so no credentials touch this
 * store (per the [PalaceLibraryConfig] contract's future-state note).
 */
@Singleton
class PalaceLibraryConfigImpl(
    private val store: DataStore<Preferences>,
) : PalaceLibraryConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(context.palaceLibraryDataStore)

    // DataStore replays the latest value on subscribe, satisfying the
    // PalaceLibraryConfig "emit current value on subscribe" contract.
    override val libraryRootUrl: Flow<String?> = store.data
        .map { it[PalaceLibraryKeys.ROOT_URL]?.ifBlank { null } }
        .distinctUntilChanged()

    /** Snapshot for the config contributor's echo path. */
    suspend fun current(): String? =
        store.data.first()[PalaceLibraryKeys.ROOT_URL]?.ifBlank { null }

    /** Persist the user's library root URL. Blank clears it. */
    suspend fun setRootUrl(url: String?) {
        val trimmed = url?.trim().orEmpty()
        store.edit { prefs ->
            if (trimmed.isBlank()) prefs.remove(PalaceLibraryKeys.ROOT_URL)
            else prefs[PalaceLibraryKeys.ROOT_URL] = trimmed
        }
    }
}
