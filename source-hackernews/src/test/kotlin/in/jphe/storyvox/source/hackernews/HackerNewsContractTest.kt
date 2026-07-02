package `in`.jphe.storyvox.source.hackernews

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * HackerNews against the shared [FictionSourceContractTest]. The Firebase +
 * Algolia hosts are pointed at the kit's MockWebServer via the [HackerNewsApi]
 * base seams; `popular()` -> `topStoryIds()` -> the `topstories` list endpoint.
 */
class HackerNewsContractTest : FictionSourceContractTest() {
    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource =
        HackerNewsSource(
            object : HackerNewsApi(client) {
                override val firebaseBase: String = baseUrl.trimEnd('/')
                override val algoliaBase: String = baseUrl.trimEnd('/')
            },
        )

    /** `/v0/topstories.json` — a JSON array of item ids. */
    override fun happyListBody(): String = "[111, 222]"

    override fun listPathFragment(): String = "topstories"
}
