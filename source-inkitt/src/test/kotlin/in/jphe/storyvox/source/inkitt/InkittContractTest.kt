package `in`.jphe.storyvox.source.inkitt

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.inkitt.net.InkittApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class InkittContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return InkittSource(
            object : InkittApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    // No server-rendered listing surface — the kit exercises search instead.
    override val exercisesPopular: Boolean = false

    override fun happyListBody(): String = InkittFixtures.SEARCH_JSON

    override fun listPathFragment(): String = "/api/3/search/stories"
}
