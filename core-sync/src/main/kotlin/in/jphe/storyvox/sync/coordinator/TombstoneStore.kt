package `in`.jphe.storyvox.sync.coordinator

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tombstonesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_sync_tombstones",
)

/**
 * Read-only / write seam over a per-domain tombstone log. Extracted so
 * tests can substitute an in-memory implementation without needing a
 * full Android context — the production [TombstoneStore] is the only
 * non-test implementation.
 */
interface TombstonesAccess {
    fun observe(domain: String): Flow<Set<String>>
    suspend fun snapshot(domain: String): Set<String>
    suspend fun add(domain: String, id: String)
    suspend fun addAll(domain: String, ids: Collection<String>)
    suspend fun forget(domain: String, id: String)
    suspend fun clear(domain: String)

    /**
     * Snapshot tombstones with their recorded timestamps (epoch ms).
     *
     * Issue #360 finding 3 (argus): the v1 [snapshot] returns
     * `Set<String>` which makes tombstones immortal — a re-add of a
     * tombstoned id gets filtered forever. The timestamped variant
     * lets [ConflictPolicies.unionWithTombstoneStamps] apply a
     * freshness window, so re-adds eventually win.
     *
     * Returns an empty map for ids that have no recorded timestamp
     * (legacy entries from before the timestamp seam landed). The
     * default implementation falls back to "stamp = 0L" for legacy
     * ids; concrete implementations override with real stamps. The
     * "stamp = 0L" fallback means a legacy tombstone is treated as
     * "infinitely old" and therefore expired immediately, which is
     * the safe default — once the user re-adds it, the re-add wins.
     */
    suspend fun snapshotWithStamps(domain: String): Map<String, Long> =
        snapshot(domain).associateWith { 0L }
}

/**
 * Per-domain tombstone log. Records deletions so a synced device can
 * propagate "I removed X" to the cloud (and from the cloud to every
 * other device on next pull).
 *
 * Why a separate store and not just `Set<id>` in InstantDB: a set without
 * a tombstone log races with a "re-add" — if device A removes X, device B
 * is offline, B re-adds X, then both sync, the union of local + remote
 * would put X back. Tombstones break that tie deterministically: removal
 * is sticky until a successful sync confirms server-side deletion, at
 * which point the tombstone is forgotten.
 *
 * Stored in DataStore (not Room) because the access pattern is
 * "small set per domain, read-write-mostly" — DataStore's preferences
 * proto gives us a Flow for free and is the lighter dep.
 *
 * Storage layout: one stringSet key per domain, with key
 * `tomb.<domain>.<id>`. We bucket per-domain so a domain can clear its
 * own tombstones without touching others.
 */
@Singleton
class TombstoneStore @Inject constructor(
    @ApplicationContext context: Context,
) : TombstonesAccess {
    private val store = context.tombstonesDataStore

    /** Clock seam — only overridden in tests. Kept off the @Inject
     *  constructor because Hilt can't resolve `() -> Long` without a
     *  named binding, and the production clock is always wall-time. */
    internal var clock: () -> Long = System::currentTimeMillis

    override fun observe(domain: String): Flow<Set<String>> = store.data.map { prefs ->
        prefs[domainKey(domain)] ?: emptySet()
    }

    override suspend fun snapshot(domain: String): Set<String> =
        observe(domain).first()

    /**
     * Issue #360 finding 3: pair each tombstone id with the epoch-ms
     * stamp recorded when it was added. Stamps live in a sibling
     * string preference key `tomb-stamp.<domain>` encoded as
     * "id1=stamp1;id2=stamp2". A separate key (instead of switching
     * the whole representation to a stringPreferencesKey) lets the
     * existing [observe] Flow keep its O(1) stringSet read and lets
     * the timestamped read pay the parse cost only when the syncer
     * actually needs the stamps.
     */
    override suspend fun snapshotWithStamps(domain: String): Map<String, Long> {
        val prefs = store.data.first()
        val ids = prefs[domainKey(domain)] ?: return emptyMap()
        val stampsBlob = prefs[stampsKey(domain)].orEmpty()
        val stamps = parseStamps(stampsBlob)
        return ids.associateWith { stamps[it] ?: 0L }
    }

    override suspend fun add(domain: String, id: String) {
        val now = clock()
        store.edit { prefs ->
            val key = domainKey(domain)
            val current = prefs[key] ?: emptySet()
            prefs[key] = current + id
            val stampsKey = stampsKey(domain)
            val stamps = parseStamps(prefs[stampsKey].orEmpty()).toMutableMap()
            stamps[id] = now
            prefs[stampsKey] = encodeStamps(stamps)
        }
    }

    override suspend fun addAll(domain: String, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val now = clock()
        store.edit { prefs ->
            val key = domainKey(domain)
            val current = prefs[key] ?: emptySet()
            prefs[key] = current + ids
            val stampsKey = stampsKey(domain)
            val stamps = parseStamps(prefs[stampsKey].orEmpty()).toMutableMap()
            for (id in ids) stamps[id] = now
            prefs[stampsKey] = encodeStamps(stamps)
        }
    }

    override suspend fun forget(domain: String, id: String) {
        store.edit { prefs ->
            val key = domainKey(domain)
            val current = prefs[key] ?: return@edit
            val next = current - id
            if (next.isEmpty()) prefs.remove(key) else prefs[key] = next
            val stampsKey = stampsKey(domain)
            val stamps = parseStamps(prefs[stampsKey].orEmpty()).toMutableMap()
            stamps.remove(id)
            if (stamps.isEmpty()) prefs.remove(stampsKey) else prefs[stampsKey] = encodeStamps(stamps)
        }
    }

    override suspend fun clear(domain: String) {
        store.edit { prefs ->
            prefs.remove(domainKey(domain))
            prefs.remove(stampsKey(domain))
        }
    }

    private fun domainKey(domain: String) = stringSetPreferencesKey("tomb.$domain")
    private fun stampsKey(domain: String) = stringPreferencesKey("tomb-stamp.$domain")

    /** Encode `Map<String, Long>` as a "id=stamp;id=stamp" blob.
     *  Cheaper than serialising JSON for what's typically <100 entries
     *  per domain, and avoids dragging the kotlinx-serialization seam
     *  into a class that's otherwise pure DataStore. */
    private fun encodeStamps(stamps: Map<String, Long>): String =
        stamps.entries.joinToString(separator = ";") { (id, stamp) -> "$id=$stamp" }

    private fun parseStamps(blob: String): Map<String, Long> {
        if (blob.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (entry in blob.split(';')) {
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val id = entry.substring(0, eq)
            val stamp = entry.substring(eq + 1).toLongOrNull() ?: continue
            out[id] = stamp
        }
        return out
    }
}

/** Helper extension so [TombstoneStore] can be constructed in tests
 *  without the full DataStore — useful for ConflictPolicies tests that
 *  exercise the union-with-tombstones path. */
fun emptyTombstones(): Set<String> = emptySet()
