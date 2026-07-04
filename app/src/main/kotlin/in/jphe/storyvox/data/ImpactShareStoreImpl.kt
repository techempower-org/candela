package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.repository.impact.ImpactShareState
import `in`.jphe.storyvox.data.repository.impact.ImpactShareStore
import `in`.jphe.storyvox.data.repository.impact.ImpactTotals
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

private val Context.impactDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_impact")

private object ImpactKeys {
    /** Coarsened cumulative hours as of last share. */
    val HOURS = intPreferencesKey("impact_last_hours")
    /** Coarsened cumulative finished chapters as of last share. */
    val CHAPTERS = intPreferencesKey("impact_last_chapters")
    /** Cumulative finished books as of last share. */
    val BOOKS = intPreferencesKey("impact_last_books")
    /** Pipe-joined set of source IDs shared as of last share. Source IDs are lower-kebab
     *  built-in identifiers (`royal-road`, `gutenberg`, …) — none contains a pipe, so it
     *  is a collision-free delimiter (same argument as RssConfigImpl's feed list). */
    val SOURCES = stringPreferencesKey("impact_last_sources")
    /** `"YYYY-MM"` of the last share; absent until the user shares once. */
    val PERIOD = stringPreferencesKey("impact_last_period")
}

/**
 * Issue #1463 — production [ImpactShareStore] backed by a tiny dedicated DataStore, kept
 * separate from `storyvox_settings` (same rationale as [RssConfigImpl]) so this
 * privacy-sensitive-adjacent bookkeeping has its own file and schema.
 *
 * Holds **no identity** — only coarse cumulative counts + the last-shared month. Wiping
 * app data resets it to [ImpactShareState.EMPTY] with no consequence beyond the next
 * share reporting a full cumulative delta.
 */
@Singleton
class ImpactShareStoreImpl(
    private val store: DataStore<Preferences>,
) : ImpactShareStore {

    @Inject constructor(@ApplicationContext context: Context) : this(context.impactDataStore)

    override suspend fun state(): ImpactShareState {
        val prefs = store.data.first()
        val period = prefs[ImpactKeys.PERIOD]
        return ImpactShareState(
            totals = ImpactTotals(
                hoursListened = prefs[ImpactKeys.HOURS] ?: 0,
                chaptersCompleted = prefs[ImpactKeys.CHAPTERS] ?: 0,
                booksCompleted = prefs[ImpactKeys.BOOKS] ?: 0,
                sourceIds = decodeSources(prefs[ImpactKeys.SOURCES]),
            ),
            lastSharedPeriod = period?.takeIf { it.isNotBlank() },
        )
    }

    override suspend fun recordShared(totals: ImpactTotals, period: String) {
        store.edit { prefs ->
            prefs[ImpactKeys.HOURS] = totals.hoursListened
            prefs[ImpactKeys.CHAPTERS] = totals.chaptersCompleted
            prefs[ImpactKeys.BOOKS] = totals.booksCompleted
            prefs[ImpactKeys.SOURCES] = encodeSources(totals.sourceIds)
            prefs[ImpactKeys.PERIOD] = period
        }
    }

    private fun encodeSources(ids: Set<String>): String =
        ids.filter { it.isNotBlank() }.sorted().joinToString("|")

    private fun decodeSources(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
