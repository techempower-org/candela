package `in`.jphe.storyvox.source.primegaming

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * Prime Gaming against the shared contract kit. This FAILS until you wire
 * [PrimeGamingSource.popular] (and friends) through [PrimeGamingApi.request]:
 * the stub never hits the network, so the "network work leaves the caller
 * thread" and auth/rate-limit checks fail honestly. Replace [happyListBody] /
 * [listPathFragment] with your real list endpoint, make the source talk to the
 * Api, and turn this green. See docs/CONTRIBUTING-SOURCES.md.
 */
class PrimeGamingContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return PrimeGamingSource(
            object : PrimeGamingApi(client) {
                override val baseUrl: String get() = host
            },
        )
    }

    /** Replace with a trimmed real response body from your list endpoint. */
    override fun happyListBody(): String = "{}"

    /** Replace with a path fragment your popular()/list endpoint hits. */
    override fun listPathFragment(): String = "primegaming"
}
