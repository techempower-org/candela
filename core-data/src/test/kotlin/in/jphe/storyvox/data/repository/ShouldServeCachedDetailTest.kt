package `in`.jphe.storyvox.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the stale-while-revalidate skip decision (#1314) + the #1621
 * plan-version bypass. Pure JVM — no Room, no repository wiring.
 */
class ShouldServeCachedDetailTest {

    private val ttl = 300_000L // mirrors METADATA_TTL_MS; the guard takes it as a param
    private val fresh = 1_000L // age well within the TTL

    @Test fun `serves cache when hydrated, current plan, within TTL`() {
        assertTrue(
            shouldServeCachedDetail(
                force = false, chaptersOrphan = false, planStale = false,
                metadataFetchedAt = 1L, ageMs = fresh, ttlMs = ttl,
            ),
        )
    }

    @Test fun `1621 — does NOT serve a plan-stale cache, even within TTL`() {
        // The core #1621 fix: a list cached under an older CHAPTER_PLAN_VERSION
        // must re-fetch on next open regardless of the 5-min TTL.
        assertFalse(
            shouldServeCachedDetail(
                force = false, chaptersOrphan = false, planStale = true,
                metadataFetchedAt = 1L, ageMs = fresh, ttlMs = ttl,
            ),
        )
    }

    @Test fun `does not serve when forced`() {
        assertFalse(
            shouldServeCachedDetail(
                force = true, chaptersOrphan = false, planStale = false,
                metadataFetchedAt = 1L, ageMs = fresh, ttlMs = ttl,
            ),
        )
    }

    @Test fun `does not serve when chapter rows are orphaned`() {
        assertFalse(
            shouldServeCachedDetail(
                force = false, chaptersOrphan = true, planStale = false,
                metadataFetchedAt = 1L, ageMs = fresh, ttlMs = ttl,
            ),
        )
    }

    @Test fun `does not serve a placeholder row (metadataFetchedAt 0)`() {
        assertFalse(
            shouldServeCachedDetail(
                force = false, chaptersOrphan = false, planStale = false,
                metadataFetchedAt = 0L, ageMs = fresh, ttlMs = ttl,
            ),
        )
    }

    @Test fun `does not serve past the TTL`() {
        assertFalse(
            shouldServeCachedDetail(
                force = false, chaptersOrphan = false, planStale = false,
                metadataFetchedAt = 1L, ageMs = ttl + 1, ttlMs = ttl,
            ),
        )
    }

    @Test fun `serves under negative age (clock skew) — safe direction`() {
        assertTrue(
            shouldServeCachedDetail(
                force = false, chaptersOrphan = false, planStale = false,
                metadataFetchedAt = 1L, ageMs = -5_000L, ttlMs = ttl,
            ),
        )
    }

    @Test fun `CHAPTER_PLAN_VERSION is positive so migration-default-0 rows revalidate once`() {
        // The v19 migration adds the column with DEFAULT 0; every pre-existing
        // row must read as older-than-current (planStale) so it re-plans once.
        assertTrue(0 < CHAPTER_PLAN_VERSION)
    }
}
