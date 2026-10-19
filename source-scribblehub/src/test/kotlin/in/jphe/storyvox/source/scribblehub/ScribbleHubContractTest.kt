package `in`.jphe.storyvox.source.scribblehub

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.scribblehub.net.ScribbleHubApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class ScribbleHubContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return ScribbleHubSource(
            object : ScribbleHubApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = ScribbleHubFixtures.LIST

    override fun listPathFragment(): String = "/series-ranking/"
}
