package `in`.jphe.storyvox.data.source.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.5.76 — codec round-trip tests for the JSON set persisted under
 * `pref_source_favorites_v1`.
 *
 * Mirrors [SourcePluginsEnabledCodecTest]'s failure-mode contract:
 * - Null / blank / unparseable input returns an empty set (no throw).
 * - Encode → decode round-trips intact regardless of insertion order.
 * - Empty set encodes to a well-formed JSON array, not an empty string.
 */
class SourceFavoritesCodecTest {

    @Test fun `null input decodes to empty set`() {
        assertEquals(emptySet<String>(), decodeSourceFavoritesJson(null))
    }

    @Test fun `blank input decodes to empty set`() {
        assertEquals(emptySet<String>(), decodeSourceFavoritesJson(""))
        assertEquals(emptySet<String>(), decodeSourceFavoritesJson("   "))
    }

    @Test fun `unparseable input decodes to empty set without throwing`() {
        assertEquals(emptySet<String>(), decodeSourceFavoritesJson("not json"))
        assertEquals(emptySet<String>(), decodeSourceFavoritesJson("{garbage"))
    }

    @Test fun `round-trip preserves members`() {
        val original = setOf("royalroad", "ao3", "gutenberg")

        val encoded = encodeSourceFavoritesJson(original)
        val decoded = decodeSourceFavoritesJson(encoded)

        assertEquals(original, decoded)
    }

    @Test fun `empty set round-trips`() {
        val encoded = encodeSourceFavoritesJson(emptySet())
        val decoded = decodeSourceFavoritesJson(encoded)
        assertEquals(emptySet<String>(), decoded)
    }

    @Test fun `empty set encodes to a non-blank JSON array`() {
        // Important — encoding must not produce "" or null. The DataStore
        // round-trip writes whatever this returns; if it's blank, the
        // decoder's null/blank guard kicks in next read and we'd lose
        // an *intentionally empty* favorites set on every save.
        val encoded = encodeSourceFavoritesJson(emptySet())
        assertTrue("expected non-blank encoding, got '$encoded'", encoded.isNotBlank())
    }
}
