package `in`.jphe.storyvox.source.aff

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.aff.net.AdultFanfictionApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

class AdultFanfictionContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return AdultFanfictionSource(
            object : AdultFanfictionApi(client) {
                override val baseUrl: String get() = host
                override fun archiveBase(archive: String): String = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = AffFixtures.LIST

    /** popular() hits the default archive's index sorted by reviews. */
    override fun listPathFragment(): String = "index.php?sort=reviews"
}
