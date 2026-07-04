package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.primegaming.config.PrimeGamingConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.primeGamingDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "storyvox_primegaming")

private object PrimeGamingKeys {
    /** The user's feed-URL override. Absent/blank = use
     *  [PrimeGamingConfig.DEFAULT_FEED_URL]. */
    val FEED_URL = stringPreferencesKey("pref_primegaming_feed_url")
}

/**
 * Issue #1494 — production [PrimeGamingConfig] backed by a tiny dedicated
 * DataStore, kept separate from `storyvox_settings` so this one tunable can't
 * churn the main preference schema (same pattern as [RssConfigImpl] /
 * [RadioConfigImpl]).
 *
 * This is the **single-maintainer mitigation** made real: the LootScraper feed
 * ships as the default, but if it moves domains again (it already has once) or
 * the user wants a self-hosted instance, the override is persisted here and
 * picked up live via [feedUrlFlow]. A null/blank override reverts to the
 * baked-in default, so "reset" needs no extra key.
 */
@Singleton
class PrimeGamingConfigImpl(
    private val store: DataStore<Preferences>,
) : PrimeGamingConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(context.primeGamingDataStore)

    override val feedUrlFlow: Flow<String> = store.data
        .map { prefs -> prefs[PrimeGamingKeys.FEED_URL].orDefault() }
        .distinctUntilChanged()

    override suspend fun feedUrl(): String =
        store.data.first()[PrimeGamingKeys.FEED_URL].orDefault()

    override suspend fun setFeedUrl(url: String?) {
        val trimmed = url?.trim().orEmpty()
        store.edit { prefs ->
            if (trimmed.isEmpty()) prefs.remove(PrimeGamingKeys.FEED_URL)
            else prefs[PrimeGamingKeys.FEED_URL] = trimmed
        }
    }

    private fun String?.orDefault(): String =
        this?.trim()?.ifBlank { null } ?: PrimeGamingConfig.DEFAULT_FEED_URL
}
