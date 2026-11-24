package `in`.jphe.storyvox.source.endless.net

import `in`.jphe.storyvox.source.endless.FakeEndlessConfig
import `in`.jphe.storyvox.source.endless.config.EndlessConfigState
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host normalization, the LAN guard, and media-URL re-hosting.
 *
 * The LAN guard is the app-layer half of "this source cannot leak story
 * requests to the open internet": the daemon has no TLS and no auth, so a
 * typo in the host field must fail closed rather than send cleartext
 * requests to an arbitrary address.
 */
class EndlessLitrpgApiTest {

    private fun api(host: String) =
        EndlessLitrpgApi(OkHttpClient(), FakeEndlessConfig(host))

    private fun base(host: String): String? =
        api(host).baseUrlOrNull(EndlessConfigState(host))

    // ─── host normalization ────────────────────────────────────────────

    @Test fun `a bare LAN address defaults to http because the daemon has no TLS`() {
        assertEquals("http://192.168.1.129:8093", base("192.168.1.129:8093"))
    }

    @Test fun `an explicit scheme is honoured for a TLS-fronted proxy`() {
        assertEquals("https://192.168.1.129:8093", base("https://192.168.1.129:8093"))
    }

    @Test fun `a mixed-case scheme is stripped correctly`() {
        // A case-insensitive test paired with a case-sensitive removal is a
        // bug that has shipped in this codebase before (PalaceDaemonApi).
        assertEquals("http://192.168.1.129:8093", base("Http://192.168.1.129:8093"))
        assertEquals("https://192.168.1.129:8093", base("HTTPS://192.168.1.129:8093"))
    }

    @Test fun `a trailing slash is tolerated`() {
        assertEquals("http://192.168.1.129:8093", base("http://192.168.1.129:8093/"))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        assertEquals("http://192.168.1.129:8093", base("  192.168.1.129:8093  "))
    }

    @Test fun `loopback is accepted so local dev and unit tests work`() {
        assertEquals("http://127.0.0.1:8093", base("127.0.0.1:8093"))
    }

    @Test fun `an mDNS name is accepted without a blocking resolve`() {
        assertEquals("http://story.local:8093", base("story.local:8093"))
    }

    // ─── the guard ─────────────────────────────────────────────────────

    @Test fun `a blank host is rejected`() {
        assertNull(base(""))
        assertNull(base("   "))
        assertNull(base("http://"))
    }

    // ─── the tailnet ─────────────────────────────────────────────

    @Test fun `a tailnet address is accepted because CGNAT space never leaves the realm`() {
        // 100.64.0.0/10 is carrier-grade NAT space, which Tailscale assigns to every node;
        // the phone reaches the daemon over it exactly as it would over the LAN.
        assertEquals("http://100.80.12.34:8093", base("100.80.12.34:8093"))
        assertEquals("http://100.98.1.2:8093", base("100.98.1.2:8093"))
        assertEquals("http://100.64.0.1:8093", base("100.64.0.1:8093"))            // the range's first host
        assertEquals("http://100.127.255.254:8093", base("100.127.255.254:8093"))  // and its last
    }

    @Test fun `the addresses either side of the CGNAT range stay refused`() {
        assertNull(base("100.63.255.255:8093"))
        assertNull(base("100.128.0.0:8093"))
    }

    @Test fun `a Tailscale IPv6 address is accepted and a public IPv6 address refused`() {
        // fd7a:115c:a1e0::/48 is the ULA prefix every tailnet node gets an IPv6 address in.
        assertEquals("http://[fd7a:115c:a1e0::1]:8093", base("[fd7a:115c:a1e0::1]:8093"))
        assertEquals("http://[fd7a:115c:a1e0:ab12:4843:cd96:6255:1234]:8093", base("[fd7a:115c:a1e0:ab12:4843:cd96:6255:1234]:8093"))
        assertNull(base("[2001:4860:4860::8888]:8093"))   // Google's resolver over IPv6
        assertNull(base("[fd7a:115c:a1e1::1]:8093"))      // a ULA one bit off the prefix
    }

    @Test fun `an IPv4-mapped IPv6 address is judged by the IPv4 it carries`() {
        // Built from the 16 mapped bytes through the resolver seam: a dotted "::ffff:a.b.c.d"
        // literal resolves to a plain 4-byte address and never reaches the 16-byte branch.
        fun mapped(a: Int, b: Int, c: Int, d: Int) = ByteArray(16).also {
            it[10] = 0xff.toByte(); it[11] = 0xff.toByte()
            it[12] = a.toByte(); it[13] = b.toByte(); it[14] = c.toByte(); it[15] = d.toByte()
        }
        val tailnet = api("mapped-tailnet.example:8093")
        tailnet.resolve = { name -> java.net.InetAddress.getByAddress(name, mapped(100, 80, 12, 34)) }
        assertEquals("http://mapped-tailnet.example:8093", tailnet.baseUrlOrNull(EndlessConfigState("mapped-tailnet.example:8093")))
        val public = api("mapped-public.example:8093")
        public.resolve = { name -> java.net.InetAddress.getByAddress(name, mapped(8, 8, 8, 8)) }
        assertNull(public.baseUrlOrNull(EndlessConfigState("mapped-public.example:8093")))
    }

    @Test fun `the tailnet helper is byte-identical in the palace twin so the two cannot drift`() {
        // The two source modules share no common module; the guard is duplicated on purpose
        // (xreview #1894 round 2) and this test is the only thing holding the copies together.
        fun helper(path: String): String {
            val text = java.io.File(path).readText()
            val start = text.indexOf("True for an address Tailscale assigns")
            val end = text.indexOf("private fun isV4Mapped", start).let { text.indexOf("\n\n", it) }
            require(start >= 0 && end > start) { "helper block not found in $path" }
            return text.substring(start, end)
        }
        // Module tests run with the module directory as the working directory.
        val endless = helper("src/main/kotlin/in/jphe/storyvox/source/endless/net/EndlessLitrpgApi.kt")
        val palace = helper("../source-mempalace/src/main/kotlin/in/jphe/storyvox/source/mempalace/net/PalaceDaemonApi.kt")
        assertEquals(endless, palace)
    }

    @Test fun `a MagicDNS name that resolves into the tailnet is accepted`() {
        val api = api("familiar.tail1234.ts.net:8093")
        api.resolve = { name ->
            assertEquals("familiar.tail1234.ts.net", name)
            java.net.InetAddress.getByAddress(name, byteArrayOf(100, 98, 1, 2))
        }
        assertEquals("http://familiar.tail1234.ts.net:8093", api.baseUrlOrNull(EndlessConfigState("familiar.tail1234.ts.net:8093")))
    }

    @Test fun `a name that does not resolve fails closed`() {
        val api = api("nowhere.tail1234.ts.net:8093")
        api.resolve = { name -> throw java.net.UnknownHostException(name) }
        assertNull(api.baseUrlOrNull(EndlessConfigState("nowhere.tail1234.ts.net:8093")))
    }

    @Test fun `a public address is rejected`() {
        // 8.8.8.8 is not site-local, so it must never be dialled with a
        // cleartext, unauthenticated request.
        assertNull(base("8.8.8.8:8093"))
    }

    @Test fun `a host carrying a path is rejected because this client owns the path`() {
        assertNull(base("192.168.1.129:8093/api"))
    }

    @Test fun `an unresolvable host fails closed`() {
        // We cannot prove it's LAN-local, so it isn't treated as one.
        assertNull(base("no-such-host.invalid"))
    }

    // ─── media re-hosting ──────────────────────────────────────────────

    @Test fun `a daemon-reported media url is re-hosted on the configured authority`() = runBlocking {
        // The daemon builds absolute URLs from its OWN bound address. If we
        // passed those straight to the player, media would bypass the host
        // the user configured — missing any hostname-scoped cleartext
        // allowlist and breaking silently when the daemon moves.
        assertEquals(
            "http://story.local:8093/media/0005.mp3",
            api("story.local:8093").mediaUrl("http://192.168.1.129:8093/media/0005.mp3"),
        )
    }

    @Test fun `re-hosting preserves a query string`() = runBlocking {
        assertEquals(
            "http://story.local:8093/media/0005.mp3?v=2",
            api("story.local:8093").mediaUrl("http://192.168.1.129:8093/media/0005.mp3?v=2"),
        )
    }

    @Test fun `a null or blank media url stays null so the chapter reads as text`() = runBlocking {
        val a = api("story.local:8093")
        assertNull(a.mediaUrl(null))
        assertNull(a.mediaUrl(""))
        assertNull(a.mediaUrl("   "))
    }

    @Test fun `a media url is null when the host is not configured`() = runBlocking {
        assertNull(api("").mediaUrl("http://192.168.1.129:8093/media/0005.mp3"))
    }

    @Test fun `a media url is null when the host fails the LAN guard`() = runBlocking {
        assertNull(api("8.8.8.8:8093").mediaUrl("http://192.168.1.129:8093/media/0005.mp3"))
    }

    @Test fun `pathOf keeps the path and drops the authority`() {
        assertEquals(
            "/media/0005.mp3",
            EndlessLitrpgApi.pathOf("http://192.168.1.129:8093/media/0005.mp3"),
        )
        assertNull(EndlessLitrpgApi.pathOf("http://192.168.1.129:8093"))
    }
}
