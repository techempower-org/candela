package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.repository.sync.FollowedTagsStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Single-file owner of the per-source followed-tags set + tombstones
 * + sync metadata (issue #178).
 *
 * **Two DataStores, by design:**
 *
 *  - `storyvox_tag_sync` — large per-source JSON payloads (the
 *    followed-tag set + tombstone map). Intentionally NOT
 *    round-tripped through `:core-sync` because the tag set
 *    itself is mirrored via Royal Road's server-side preference
 *    (the canonical source-of-truth for RR-side tags), and double-
 *    merging through both InstantDB LWW and RR LWW would risk
 *    collisions with different freshness windows.
 *
 *  - `storyvox_settings` — the two metadata keys (`pref_rr_tag_sync_enabled`,
 *    `pref_rr_tag_sync_last_synced_at`). Both ARE in the
 *    `:core-sync` allowlist (see [SettingsRepositoryUiImpl.SYNC_ALLOWLIST])
 *    so a user who flips "sync with RR off" on their phone sees
 *    the toggle reflected on their tablet, and so the "Last
 *    synced" pill shows the freshest stamp across devices.
 *
 *    DataStore is a per-name singleton per process — opening
 *    `storyvox_settings` from this file points at the same
 *    on-disk file `SettingsRepositoryUiImpl.settingsDataStore`
 *    uses. Concurrent writes are safe because DataStore
 *    serialises all `edit` calls internally.
 *
 * Wire shapes (storyvox_tag_sync):
 *  - `tags:<sourceId>` → JSON `{ "tags": ["action", "litrpg", ...] }`
 *  - `tombstones:<sourceId>` → JSON `{ "action": 1715000000000, ... }`
 *
 * Wire shapes (storyvox_settings):
 *  - `pref_rr_tag_sync_enabled` → Boolean
 *  - `pref_rr_tag_sync_last_synced_at` → Long
 *
 * The per-sourceId keying for the large payloads means a future
 * AO3 / Discord tag-sync doesn't need a new file or schema —
 * just a different sourceId argument at the call site.
 */
private val Context.tagSyncDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_tag_sync",
)

@Singleton
class FollowedTagsStoreImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : FollowedTagsStore {

    private val store: DataStore<Preferences> get() = context.tagSyncDataStore
    // Shares the package-internal `settingsDataStore` delegate declared in
    // SettingsRepositoryUiImpl. Declaring a second `preferencesDataStore(name=...)`
    // with the same name throws IllegalStateException at runtime (#522).
    private val metadataStore: DataStore<Preferences> get() = context.settingsDataStore

    private fun tagsKey(sourceId: String) = stringPreferencesKey("tags:$sourceId")
    private fun tombstonesKey(sourceId: String) = stringPreferencesKey("tombstones:$sourceId")

    /**
     * Issue #178 — per-source last-synced metadata key. Today
     * scoped only to Royal Road; the per-sourceId pattern leaves
     * room for AO3 / Discord without touching the wire schema.
     * The `pref_rr_*` literal name is preserved for RR because
     * it's already in the `:core-sync` allowlist; future sources
     * would add their own keyed entries.
     */
    private fun lastSyncedAtKey(sourceId: String) = longPreferencesKey(
        when (sourceId) {
            `in`.jphe.storyvox.data.source.SourceIds.ROYAL_ROAD -> "pref_rr_tag_sync_last_synced_at"
            else -> "pref_${sourceId}_tag_sync_last_synced_at"
        }
    )

    private fun syncEnabledKey(sourceId: String) = booleanPreferencesKey(
        when (sourceId) {
            `in`.jphe.storyvox.data.source.SourceIds.ROYAL_ROAD -> "pref_rr_tag_sync_enabled"
            else -> "pref_${sourceId}_tag_sync_enabled"
        }
    )

    override fun followedTags(sourceId: String): Flow<Set<String>> {
        val key = tagsKey(sourceId)
        return store.data.map { prefs -> decodeTagSet(prefs[key]) }
    }

    override suspend fun snapshotFollowedTags(sourceId: String): Set<String> =
        followedTags(sourceId).first()

    override suspend fun tombstones(sourceId: String): Map<String, Long> {
        val raw = store.data.first()[tombstonesKey(sourceId)]
        return decodeTombstones(raw)
    }

    override suspend fun replaceFollowedTags(sourceId: String, tags: Set<String>) {
        val key = tagsKey(sourceId)
        store.edit { prefs -> prefs[key] = encodeTagSet(tags) }
    }

    override suspend fun setTagFollowed(
        sourceId: String,
        tag: String,
        followed: Boolean,
        at: Long,
    ) {
        val tagsK = tagsKey(sourceId)
        val tombsK = tombstonesKey(sourceId)
        store.edit { prefs ->
            val existingTags = decodeTagSet(prefs[tagsK])
            val existingTombs = decodeTombstones(prefs[tombsK])
            if (followed) {
                prefs[tagsK] = encodeTagSet(existingTags + tag)
                // Re-add clears the tombstone — the user changed
                // their mind, the prior remove is no longer load-
                // bearing for merge.
                if (existingTombs.containsKey(tag)) {
                    val cleared = existingTombs.toMutableMap().apply { remove(tag) }
                    prefs[tombsK] = encodeTombstones(cleared)
                }
            } else {
                prefs[tagsK] = encodeTagSet(existingTags - tag)
                // Record (or overwrite) the tombstone with the
                // current wall-clock stamp — the merge layer uses
                // this for the 5-min prefer-local window.
                val newTombs = existingTombs.toMutableMap().apply { put(tag, at) }
                prefs[tombsK] = encodeTombstones(newTombs)
            }
        }
    }

    override suspend fun forgetTombstone(sourceId: String, tag: String) {
        val key = tombstonesKey(sourceId)
        store.edit { prefs ->
            val existing = decodeTombstones(prefs[key])
            if (existing.containsKey(tag)) {
                val cleared = existing.toMutableMap().apply { remove(tag) }
                prefs[key] = encodeTombstones(cleared)
            }
        }
    }

    override fun lastSyncedAt(sourceId: String): Flow<Long> {
        val key = lastSyncedAtKey(sourceId)
        // Round-tripped via `:core-sync` so the freshest stamp
        // across devices wins (issue #178). Reads/writes hit the
        // main settings DataStore.
        return metadataStore.data.map { prefs -> prefs[key] ?: 0L }
    }

    override suspend fun stampSyncedAt(sourceId: String, at: Long) {
        val key = lastSyncedAtKey(sourceId)
        metadataStore.edit { prefs -> prefs[key] = at }
    }

    override fun syncEnabled(sourceId: String): Flow<Boolean> {
        val key = syncEnabledKey(sourceId)
        // Default true — once a user is signed in to a source
        // we want sync on by default. The Settings UI's toggle
        // writes the explicit false when the user opts out.
        // Round-tripped via `:core-sync` (issue #178 — "Add to
        // :core-sync allowlist so the sync state round-trips
        // across devices.")
        return metadataStore.data.map { prefs -> prefs[key] ?: true }
    }

    override suspend fun setSyncEnabled(sourceId: String, enabled: Boolean) {
        val key = syncEnabledKey(sourceId)
        metadataStore.edit { prefs -> prefs[key] = enabled }
    }

    // ── Codec helpers ────────────────────────────────────────────

    private val json: Json = Json { ignoreUnknownKeys = true }

    private fun encodeTagSet(tags: Set<String>): String {
        // Stable order so two devices with the same set produce
        // byte-identical wire payloads (matches the contract in
        // SettingsSnapshotSource.snapshot's kdoc).
        val sorted = tags.toSortedSet().toList()
        val arr = kotlinx.serialization.json.JsonArray(sorted.map { JsonPrimitive(it) })
        val obj: JsonElement = JsonObject(mapOf("tags" to arr))
        return obj.toString()
    }

    private fun decodeTagSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            val arr = obj["tags"] ?: return emptySet()
            val list = (arr as? kotlinx.serialization.json.JsonArray) ?: return emptySet()
            list.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun encodeTombstones(map: Map<String, Long>): String {
        val sorted = map.toSortedMap()
        val obj: JsonElement = JsonObject(sorted.mapValues { JsonPrimitive(it.value) as JsonElement })
        return obj.toString()
    }

    private fun decodeTombstones(raw: String?): Map<String, Long> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = json.parseToJsonElement(raw).jsonObject
            obj.mapNotNull { (k, v) ->
                val stamp = (v as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
                k to stamp
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
