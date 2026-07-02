package `in`.jphe.storyvox.source.rss.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1489 — pure, plain-JUnit tests for the feed-cache freshness
 * decision (no Android deps, per the project's "JUnit 4, no Robolectric"
 * convention). This is the logic that makes a chapter tap after the detail
 * list has loaded a cache hit instead of a fresh network round-trip.
 */
class TtlCacheTest {

    @Test
    fun `get returns a value stored within the TTL`() {
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { 0L })
        cache.put("k", "v")
        assertEquals("v", cache.get("k"))
    }

    @Test
    fun `get returns null for an absent key`() {
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { 0L })
        assertNull(cache.get("missing"))
    }

    @Test
    fun `value just inside the TTL boundary is still a hit`() {
        var clock = 0L
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { clock })
        cache.put("k", "v")
        clock = 999L // age 999ms < 1000ms TTL
        assertEquals("v", cache.get("k"))
    }

    @Test
    fun `entry expires exactly at the TTL boundary`() {
        var clock = 0L
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { clock })
        cache.put("k", "v")
        clock = 1_000L // age == TTL → expired (strict <)
        assertNull(cache.get("k"))
    }

    @Test
    fun `get returns null well after expiry`() {
        var clock = 0L
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { clock })
        cache.put("k", "v")
        clock = 5_000L
        assertNull(cache.get("k"))
    }

    @Test
    fun `put refreshes the stored timestamp`() {
        var clock = 0L
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { clock })
        cache.put("k", "v1")
        clock = 900L
        cache.put("k", "v2") // re-stamp at t=900
        clock = 1_800L // 900ms since the re-stamp → still fresh
        assertEquals("v2", cache.get("k"))
    }

    @Test
    fun `clear drops all entries`() {
        val cache = TtlCache<String>(ttlMillis = 1_000L, now = { 0L })
        cache.put("a", "1")
        cache.put("b", "2")
        cache.clear()
        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
    }
}
