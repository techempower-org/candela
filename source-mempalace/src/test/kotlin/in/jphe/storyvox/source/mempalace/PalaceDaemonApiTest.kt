package `in`.jphe.storyvox.source.mempalace

import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfigState
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the parts of [PalaceDaemonApi] that don't need the
 * network — LAN-only authority gating and MCP-envelope unwrap. The
 * actual HTTP path is tested by manual smoke against a live daemon.
 */
class PalaceDaemonApiTest {

    @Test fun `accepts loopback`() {
        assertNotNull(api().baseUrlOrNull(cfg("127.0.0.1:8085")))
        assertNotNull(api().baseUrlOrNull(cfg("localhost:8085")))
    }

    @Test fun `accepts mDNS local hostnames`() {
        // .local hosts don't resolve in the test env so we only test
        // that the rejection path doesn't fire on them — actual
        // resolution happens at request time.
        assertNotNull(api().baseUrlOrNull(cfg("palace.local:8085")))
        assertNotNull(api().baseUrlOrNull(cfg("disks.local")))
    }

    @Test fun `accepts RFC1918 IPv4 ranges`() {
        assertNotNull(api().baseUrlOrNull(cfg("10.0.6.50:8085")))
        assertNotNull(api().baseUrlOrNull(cfg("192.168.1.10:8085")))
        assertNotNull(api().baseUrlOrNull(cfg("172.16.0.5")))
    }

    @Test fun `accepts allowlisted palace dot jphe dot in`() {
        assertNotNull(api().baseUrlOrNull(cfg("palace.jphe.in")))
    }

    @Test fun `accepts a tailnet address because CGNAT space never leaves the realm`() {
        // 100.64.0.0/10 is carrier-grade NAT space, which Tailscale assigns to every node;
        // the phone reaches the daemon over it exactly as it would over the LAN.
        assertNotNull(api().baseUrlOrNull(cfg("100.80.12.34:8085")))
        assertNotNull(api().baseUrlOrNull(cfg("100.127.255.254:8085")))
        assertNull(api().baseUrlOrNull(cfg("100.128.0.0:8085")))   // one past the range
    }

    @Test fun `rejects public IP addresses`() {
        // 8.8.8.8 is Google's resolver — reachable but emphatically
        // not the user's home palace.
        assertNull(api().baseUrlOrNull(cfg("8.8.8.8:8085")))
    }

    @Test fun `rejects empty host`() {
        assertNull(api().baseUrlOrNull(cfg("")))
    }

    @Test fun `rejects host with embedded path`() {
        // The host field is for host[:port] only — an embedded path
        // like `palace.local/api` is malformed.
        assertNull(api().baseUrlOrNull(cfg("palace.local/api")))
    }

    @Test fun `honors user-typed https scheme`() {
        // #342 — TLS-fronted palace proxies (Caddy in front of the daemon)
        // need the caller to opt into HTTPS by typing the scheme. Previously
        // we stripped + forced http://, which made the request hit Caddy
        // on :80 and bounce with a 308 redirect storyvox couldn't follow.
        val url = api().baseUrlOrNull(cfg("https://palace.jphe.in"))
        assertEquals("https://palace.jphe.in", url)
    }

    @Test fun `accepts user-typed http scheme`() {
        val url = api().baseUrlOrNull(cfg("http://palace.local:8085"))
        assertEquals("http://palace.local:8085", url)
    }

    @Test fun `defaults to http when no scheme typed`() {
        // Bare hosts default to http:// — backwards-compatible with the
        // pre-#342 behavior for users on LAN daemons without TLS.
        val url = api().baseUrlOrNull(cfg("palace.local:8085"))
        assertEquals("http://palace.local:8085", url)
    }

    @Test fun `normalizes mixed-case scheme`() {
        // Copilot caught: original implementation had case-insensitive
        // startsWith but case-sensitive removePrefix, so `Https://host`
        // would slip through un-stripped. Verify the regex-based strip
        // handles mixed case AND emits a lowercase scheme.
        assertEquals(
            "https://palace.jphe.in",
            api().baseUrlOrNull(cfg("HTTPS://palace.jphe.in")),
        )
        assertEquals(
            "http://palace.local:8085",
            api().baseUrlOrNull(cfg("HtTp://palace.local:8085")),
        )
    }

    @Test fun `unwraps MCP text envelope`() {
        val envelope = """
            {"jsonrpc":"2.0","id":1,"result":{"content":[
              {"type":"text","text":"{\"drawer_id\":\"drawer_x\",\"content\":\"hi\",\"wing\":\"p\",\"room\":\"r\"}"}
            ]}}
        """.trimIndent()
        val text = api().unwrapMcpTextResult(envelope)
        assertEquals(
            "{\"drawer_id\":\"drawer_x\",\"content\":\"hi\",\"wing\":\"p\",\"room\":\"r\"}",
            text,
        )
    }

    @Test fun `unwrap returns null on isError envelope`() {
        val envelope = """
            {"jsonrpc":"2.0","id":1,"result":{"isError":true,"content":[]}}
        """.trimIndent()
        assertNull(api().unwrapMcpTextResult(envelope))
    }

    @Test fun `unwrap returns null on missing content array`() {
        val envelope = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        assertNull(api().unwrapMcpTextResult(envelope))
    }

    @Test fun `unwrap returns null on malformed JSON`() {
        assertNull(api().unwrapMcpTextResult("not json"))
    }

    private fun cfg(host: String): PalaceConfigState =
        PalaceConfigState(host = host, apiKey = "")

    private fun api(): PalaceDaemonApi = PalaceDaemonApi(
        httpClient = OkHttpClient(),
        config = object : PalaceConfig {
            override val state: Flow<PalaceConfigState> = flowOf(PalaceConfigState("", ""))
            override suspend fun current(): PalaceConfigState = PalaceConfigState("", "")
        },
    )
}
