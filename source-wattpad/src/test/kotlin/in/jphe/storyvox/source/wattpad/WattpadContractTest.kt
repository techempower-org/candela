package `in`.jphe.storyvox.source.wattpad

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.wattpad.net.WattpadApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class WattpadContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return WattpadSource(
            object : WattpadApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = WattpadFixtures.LIST

    override fun listPathFragment(): String = "/api/v3/stories?filter=hot"
}
