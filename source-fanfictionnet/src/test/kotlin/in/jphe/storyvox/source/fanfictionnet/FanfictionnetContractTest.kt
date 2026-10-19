package `in`.jphe.storyvox.source.fanfictionnet

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.fanfictionnet.net.FanfictionnetApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class FanfictionnetContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return FanfictionnetSource(
            object : FanfictionnetApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = FfnFixtures.LIST

    /** popular() browses the default archive (Harry Potter) sorted by favourites. */
    override fun listPathFragment(): String = "/book/Harry-Potter/"
}
