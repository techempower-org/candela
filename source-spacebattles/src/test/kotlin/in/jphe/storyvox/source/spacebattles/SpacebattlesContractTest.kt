package `in`.jphe.storyvox.source.spacebattles

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.spacebattles.net.SpacebattlesApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class SpacebattlesContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return SpacebattlesSource(
            object : SpacebattlesApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = XfFixtures.FORUM

    override fun listPathFragment(): String = "/forums/creative-writing.18/"
}
