package `in`.jphe.storyvox.data.db.migration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards [ALL_MIGRATIONS] as a chain. The round-trip tests in
 * `StoryvoxDatabaseMigrationTest` open through the production array, so this
 * is what keeps that array honest: one step per version, in order, from 1 up
 * to the current schema, with no gap, duplicate or skip.
 */
class MigrationChainTest {

    /**
     * Pinned on purpose: bump it together with `@Database(version = …)` in
     * StoryvoxDatabase when adding a migration, so the new step is reviewed.
     */
    private val currentSchemaVersion = 19

    @Test fun `the chain starts at version 1`() {
        assertEquals(1, ALL_MIGRATIONS.first().startVersion)
    }

    @Test fun `each migration steps exactly one version and follows the previous one`() {
        ALL_MIGRATIONS.forEachIndexed { i, m ->
            assertEquals("step $i must go up by one", m.startVersion + 1, m.endVersion)
            if (i > 0) {
                assertEquals(
                    "step $i must start where step ${i - 1} ended",
                    ALL_MIGRATIONS[i - 1].endVersion,
                    m.startVersion,
                )
            }
        }
    }

    @Test fun `the chain reaches the current schema version`() {
        assertEquals(currentSchemaVersion, ALL_MIGRATIONS.last().endVersion)
        assertEquals(currentSchemaVersion - 1, ALL_MIGRATIONS.size)
    }

    @Test fun `no migration object is listed twice`() {
        assertTrue(ALL_MIGRATIONS.toSet().size == ALL_MIGRATIONS.size)
    }
}
