package `in`.jphe.storyvox.playback.briefing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.briefing.BriefingPlanner
import `in`.jphe.storyvox.data.briefing.BriefingSettings
import `in`.jphe.storyvox.data.briefing.PrebuiltBriefing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the morning-briefing picker + schedule and the last prebuilt queue
 * (#1467 slice B), in its own DataStore file so it never touches the shared
 * settings repository.
 *
 * Both values are stored as JSON strings: the shape is a small, versionless
 * data class tree with defaults on every field, and `ignoreUnknownKeys` lets a
 * downgrade read a newer blob. A blob that fails to decode falls back to the
 * defaults instead of crashing the screen.
 */
@Singleton
class BriefingSettingsStore internal constructor(
    private val store: DataStore<Preferences>,
) {
    /** Hilt entry point; the primary constructor is the JVM-test seam (cf. PcmCacheConfig). */
    @Inject constructor(
        @ApplicationContext context: Context,
    ) : this(context.briefingStore)

    /** Picker + schedule, always normalized against the source catalog. */
    val settings: Flow<BriefingSettings> = store.data.map { prefs -> decodeSettings(prefs[SETTINGS_KEY]) }

    /** The last prebuilt queue, or null. */
    val prebuilt: Flow<PrebuiltBriefing?> = store.data.map { prefs -> decodePrebuilt(prefs[PREBUILT_KEY]) }

    suspend fun current(): BriefingSettings = settings.first()

    suspend fun currentPrebuilt(): PrebuiltBriefing? = prebuilt.first()

    /** Read-modify-write the settings atomically. Returns the stored value. */
    suspend fun update(transform: (BriefingSettings) -> BriefingSettings): BriefingSettings {
        var out: BriefingSettings? = null
        store.edit { prefs ->
            val next = transform(decodeSettings(prefs[SETTINGS_KEY])).let {
                it.copy(config = BriefingPlanner.normalize(it.config))
            }
            prefs[SETTINGS_KEY] = json.encodeToString(BriefingSettings.serializer(), next)
            out = next
        }
        return out ?: current()
    }

    suspend fun savePrebuilt(prebuilt: PrebuiltBriefing) {
        store.edit { it[PREBUILT_KEY] = json.encodeToString(PrebuiltBriefing.serializer(), prebuilt) }
    }

    private fun decodeSettings(raw: String?): BriefingSettings {
        // Unreadable blob → defaults (no Log here: keeps this path JVM-testable).
        val decoded = raw?.let {
            runCatching { json.decodeFromString(BriefingSettings.serializer(), it) }.getOrNull()
        } ?: BriefingSettings()
        return decoded.copy(config = BriefingPlanner.normalize(decoded.config))
    }

    private fun decodePrebuilt(raw: String?): PrebuiltBriefing? =
        raw?.let { runCatching { json.decodeFromString(PrebuiltBriefing.serializer(), it) }.getOrNull() }

    private companion object {
        val SETTINGS_KEY = stringPreferencesKey("briefing_settings_json")
        val PREBUILT_KEY = stringPreferencesKey("briefing_prebuilt_json")
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

private val Context.briefingStore: DataStore<Preferences> by preferencesDataStore(name = "morning_briefing")
