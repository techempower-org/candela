package `in`.jphe.storyvox.source.fimfiction

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.fimfiction.net.FimfictionApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class FimfictionContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return FimfictionSource(
            object : FimfictionApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = FimFixtures.LIST

    override fun listPathFragment(): String = "/stories"
}
