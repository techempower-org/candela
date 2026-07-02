package `in`.jphe.storyvox.source.rss.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * Issue #1489 — a tiny, thread-safe, time-bounded memo. Keeps the caching
 * *decision* (is this entry still fresh?) in a pure, plain-JUnit-testable
 * unit, separate from [RssSource]'s fetch/parse plumbing (which needs
 * `android.util.Xml` and so can't run under plain JUnit — project
 * convention is "JUnit 4, no Robolectric").
 *
 * The clock is injected as a `now: () -> Long` seam so tests advance time
 * deterministically. [RssSource] constructs it with `System::currentTimeMillis`,
 * so there's no Dagger binding for a `() -> Long` (no @Inject changes needed).
 *
 * Freshness is strict: an entry stored at `t` is served while
 * `now() - t < ttlMillis`, i.e. it expires exactly at the TTL boundary.
 */
internal class TtlCache<V>(
    private val ttlMillis: Long,
    private val now: () -> Long,
) {
    private data class Entry<V>(val storedAtMs: Long, val value: V)

    private val map = ConcurrentHashMap<String, Entry<V>>()

    /** The cached value if present and still within the TTL, else null.
     *  Expired entries are evicted on read (compare-and-remove so a racing
     *  [put] isn't clobbered). */
    fun get(key: String): V? {
        val entry = map[key] ?: return null
        return if (now() - entry.storedAtMs < ttlMillis) {
            entry.value
        } else {
            map.remove(key, entry)
            null
        }
    }

    /** Store [value] under [key], stamped with the current clock. */
    fun put(key: String, value: V) {
        map[key] = Entry(now(), value)
    }

    /** Drop everything (used by tests; harmless in production). */
    fun clear() = map.clear()
}
