package `in`.jphe.storyvox.source.tapas

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.tapas.net.TapasApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class TapasContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return TapasSource(
            object : TapasApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    // The novels directory is client-rendered, so browse starts from search.
    override val exercisesPopular: Boolean = false

    override fun happyListBody(): String = TapasFixtures.SEARCH

    override fun listPathFragment(): String = "/search"
}
