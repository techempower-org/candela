package `in`.jphe.storyvox.source.endless

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.endless.net.EndlessLitrpgApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * `source-endless` against the shared contract kit.
 *
 * No test seam is overridden: the source is pointed at MockWebServer by
 * handing the **real** config path a fake host, so the production code
 * runs end to end — including
 * [EndlessLitrpgApi.baseUrlOrNull]'s LAN guard, which accepts the
 * MockWebServer authority because `127.0.0.1` is loopback. Stubbing the
 * base URL instead would have skipped the guard, and the guard is the part
 * most worth having under test.
 *
 * `popular()` hits `/api/story`, so that is the routed list endpoint.
 */
class EndlessLitrpgContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource =
        EndlessLitrpgSource(
            EndlessLitrpgApi(client, FakeEndlessConfig(baseUrl.trimEnd('/'))),
        )

    /** Real `/api/story` body from the live daemon. */
    override fun happyListBody(): String = EndlessFixtures.STORY

    override fun listPathFragment(): String = "/api/story"
}
