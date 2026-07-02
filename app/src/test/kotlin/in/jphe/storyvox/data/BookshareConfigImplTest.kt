package `in`.jphe.storyvox.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1471 — [BookshareConfigImpl] behaviour. The crux is the gating
 * contract: a blank/absent key must read back as `null` so
 * `BookshareSource` behaves exactly as before the key wiring landed
 * (short-circuit to AuthRequired). Backed by the shared in-memory
 * [FakeSecrets] so no encrypted-prefs / Android runtime is needed.
 */
class BookshareConfigImplTest {

    private fun config() = BookshareConfigImpl(FakeSecrets())

    @Test
    fun `apiKey is null when unset`() = runTest {
        val c = config()
        assertNull(c.apiKey())
        assertFalse(c.isKeyConfigured())
    }

    @Test
    fun `apiKey round-trips after set`() = runTest {
        val c = config()
        c.setApiKey("partner-key-123")
        assertEquals("partner-key-123", c.apiKey())
        assertTrue(c.isKeyConfigured())
    }

    @Test
    fun `blank key reads back as null (source stays gated)`() = runTest {
        val c = config()
        c.setApiKey("   ")
        assertNull(c.apiKey())
        assertFalse(c.isKeyConfigured())
    }

    @Test
    fun `key is trimmed on store`() = runTest {
        val c = config()
        c.setApiKey("  key  ")
        assertEquals("key", c.apiKey())
    }

    @Test
    fun `null clears a previously stored key`() = runTest {
        val c = config()
        c.setApiKey("k")
        c.setApiKey(null)
        assertNull(c.apiKey())
        assertFalse(c.isKeyConfigured())
    }

    @Test
    fun `accessToken is always null until stage 2b OAuth`() = runTest {
        val c = config()
        c.setApiKey("k")
        assertNull(c.accessToken())
    }
}
