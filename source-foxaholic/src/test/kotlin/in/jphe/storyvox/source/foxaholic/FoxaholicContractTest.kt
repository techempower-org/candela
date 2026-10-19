package `in`.jphe.storyvox.source.foxaholic

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.foxaholic.net.FoxaholicApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class FoxaholicContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return FoxaholicSource(
            object : FoxaholicApi(client) {
                override val baseUrl: String get() = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = FoxaholicFixtures.LIST

    override fun listPathFragment(): String = "/novel/?m_orderby=views"
}
