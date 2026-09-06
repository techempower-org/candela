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
        assertEquals("http://192.0.2.129:8093", base("192.0.2.129:8093"))
    }

    @Test fun `an explicit scheme is honoured for a TLS-fronted proxy`() {
        assertEquals("https://192.0.2.129:8093", base("https://192.0.2.129:8093"))
    }

    @Test fun `a mixed-case scheme is stripped correctly`() {
        // A case-insensitive test paired with a case-sensitive removal is a
        // bug that has shipped in this codebase before (PalaceDaemonApi).
        assertEquals("http://192.0.2.129:8093", base("Http://192.0.2.129:8093"))
        assertEquals("https://192.0.2.129:8093", base("HTTPS://192.0.2.129:8093"))
    }

    @Test fun `a trailing slash is tolerated`() {
        assertEquals("http://192.0.2.129:8093", base("http://192.0.2.129:8093/"))
    }

    @Test fun `surrounding whitespace is tolerated`() {
        assertEquals("http://192.0.2.129:8093", base("  192.0.2.129:8093  "))
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

    @Test fun `a public address is rejected`() {
        // 8.8.8.8 is not site-local, so it must never be dialled with a
        // cleartext, unauthenticated request.
        assertNull(base("8.8.8.8:8093"))
    }

    @Test fun `a host carrying a path is rejected because this client owns the path`() {
        assertNull(base("192.0.2.129:8093/api"))
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
            api("story.local:8093").mediaUrl("http://192.0.2.129:8093/media/0005.mp3"),
        )
    }

    @Test fun `re-hosting preserves a query string`() = runBlocking {
        assertEquals(
            "http://story.local:8093/media/0005.mp3?v=2",
            api("story.local:8093").mediaUrl("http://192.0.2.129:8093/media/0005.mp3?v=2"),
        )
    }

    @Test fun `a null or blank media url stays null so the chapter reads as text`() = runBlocking {
        val a = api("story.local:8093")
        assertNull(a.mediaUrl(null))
        assertNull(a.mediaUrl(""))
        assertNull(a.mediaUrl("   "))
    }

    @Test fun `a media url is null when the host is not configured`() = runBlocking {
        assertNull(api("").mediaUrl("http://192.0.2.129:8093/media/0005.mp3"))
    }

    @Test fun `a media url is null when the host fails the LAN guard`() = runBlocking {
        assertNull(api("8.8.8.8:8093").mediaUrl("http://192.0.2.129:8093/media/0005.mp3"))
    }

    @Test fun `pathOf keeps the path and drops the authority`() {
        assertEquals(
            "/media/0005.mp3",
            EndlessLitrpgApi.pathOf("http://192.0.2.129:8093/media/0005.mp3"),
        )
        assertNull(EndlessLitrpgApi.pathOf("http://192.0.2.129:8093"))
    }
}
