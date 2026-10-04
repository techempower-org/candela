package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.library.LibrarySortMode
import `in`.jphe.storyvox.feature.library.LibrarySortStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Issue #793 — DataStore-backed persistence for the Library sort
 * dropdown. Shares the `storyvox_settings` file with the rest of the
 * app's prefs (see [SettingsRepositoryUiImpl]); DataStore is a per-
 * name singleton inside the process so the second `preferencesDataStore`
 * delegate would throw at runtime (#522). The key is the enum's `name`
 * string so future modes can be added without a migration — unknown
 * values fall back to [LibrarySortMode.DEFAULT].
 *
 * Not in the `:core-sync` allowlist (yet) — sort preference is
 * device-local UX state, not a cross-device user setting. A future PR
 * can add the key to [SettingsRepositoryUiImpl.SYNC_ALLOWLIST] if JP
 * wants tablet/phone parity here; the wire shape is stable
 * (`pref_library_sort_mode` → enum name string) so the migration
 * would be additive-only.
 */
@Singleton
class LibrarySortStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : LibrarySortStore {

    private val store get() = context.settingsDataStore

    override fun observe(): Flow<LibrarySortMode> =
        store.data.map { prefs -> LibrarySortMode.fromNameOrDefault(prefs[KEY]) }

    override suspend fun set(mode: LibrarySortMode) {
        store.edit { prefs -> prefs[KEY] = mode.name }
    }

    companion object {
        private val KEY = stringPreferencesKey("pref_library_sort_mode")
    }
}
