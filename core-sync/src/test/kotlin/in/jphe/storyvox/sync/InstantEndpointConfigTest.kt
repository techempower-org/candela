package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.client.InstantClient
import `in`.jphe.storyvox.sync.client.InstantHttpTransport
import `in`.jphe.storyvox.sync.client.TransportResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Instant endpoint is build configuration (`INSTANTDB_API_URI`), so the
 * same code can talk to Instant Cloud or a self-hosted Instant.
 */
class InstantEndpointConfigTest {

    @Test fun `unset or blank falls back to Instant Cloud`() {
        assertEquals(InstantClient.DEFAULT_API_URI, InstantClient.resolveApiUri(null))
        assertEquals(InstantClient.DEFAULT_API_URI, InstantClient.resolveApiUri("  "))
    }

    @Test fun `a self-hosted https base is used, trimmed, without a trailing slash`() {
        assertEquals(
            "https://instant.example.org",
            InstantClient.resolveApiUri(" https://instant.example.org/ "),
        )
        assertEquals(
            "https://example.org/instant",
            InstantClient.resolveApiUri("https://example.org/instant//"),
        )
    }

    @Test fun `a non-https base is refused rather than silently used`() {
        assertThrows(IllegalArgumentException::class.java) {
            InstantClient.resolveApiUri("http://instant.example.org")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstantClient.resolveApiUri("instant.example.org")
        }
        assertThrows(IllegalArgumentException::class.java) {
            InstantClient.resolveApiUri("https://")
        }
    }

    @Test fun `the client sends auth calls to the configured base`() = runTest {
        val urls = mutableListOf<String>()
        val transport = object : InstantHttpTransport {
            override suspend fun postJson(
                url: String,
                jsonBody: String,
                headers: Map<String, String>,
            ): TransportResult {
                urls += url
                return TransportResult(200, """{"sent":true}""")
            }
        }
        val base = InstantClient.resolveApiUri("https://instant.example.org/")
        InstantClient("test-app", base, transport).sendMagicCode("user@example.com")
        assertTrue(urls.single().startsWith("https://instant.example.org/runtime/auth/"))
    }
}
