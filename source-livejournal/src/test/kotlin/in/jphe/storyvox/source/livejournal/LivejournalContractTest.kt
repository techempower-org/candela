package `in`.jphe.storyvox.source.livejournal

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.livejournal.net.LivejournalApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * LiveJournal has no directory, so popular() is an honest empty page and the
 * kit exercises search() instead — "contract probe" resolves to the journal
 * `contract_probe`, whose RSS feed is served from the MockWebServer.
 */
class LivejournalContractTest : FictionSourceContractTest() {
    override val exercisesPopular: Boolean = false

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        return LivejournalSource(
            object : LivejournalApi(client) {
                override val baseUrl: String get() = host
                override fun journalBase(journal: String): String = host
                override val minIntervalMs: Long get() = 0L
            },
        )
    }

    override fun happyListBody(): String = LjFixtures.RSS

    override fun listPathFragment(): String = "/data/rss"
}
