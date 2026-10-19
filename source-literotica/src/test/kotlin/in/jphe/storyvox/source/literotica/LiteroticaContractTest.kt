package `in`.jphe.storyvox.source.literotica

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.literotica.net.LiteroticaApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class LiteroticaContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return LiteroticaSource(
            object : LiteroticaApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = LitFixtures.LIST

    override fun listPathFragment(): String = "/api/3/stories/top"
}
