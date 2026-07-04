package `in`.jphe.storyvox.source.primegaming

import dagger.Lazy
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.source.primegaming.config.PrimeGamingConfig
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingApi
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import okhttp3.OkHttpClient

/**
 * Issue #1494 — Prime Gaming against the shared contract kit (IO-pin, auth/rate
 * mapping, Cloudflare detection, blank-search sanity).
 *
 * The source reads a single configurable feed URL, so instead of the scaffold's
 * baseUrl-override seam we inject a fake [PrimeGamingConfig] pointed at the
 * MockWebServer — the kit's default router serves [happyListBody] to any path
 * containing [listPathFragment].
 */
class PrimeGamingContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val feedUrl = baseUrl.trimEnd('/') + "/lootscraper_amazon_game.xml"
        val fakeConfig = object : PrimeGamingConfig {
            override val feedUrlFlow: Flow<String> = flowOf(feedUrl)
            override suspend fun feedUrl(): String = feedUrl
            override suspend fun setFeedUrl(url: String?) = Unit
        }
        val api = PrimeGamingApi(client, object : Lazy<PrimeGamingConfig> {
            override fun get(): PrimeGamingConfig = fakeConfig
        })
        return PrimeGamingSource(api)
    }

    override fun happyListBody(): String = SAMPLE_ATOM

    override fun listPathFragment(): String = "lootscraper"

    private companion object {
        /** A trimmed, real-shaped LootScraper Amazon-Prime Atom feed (2 entries). */
        val SAMPLE_ATOM: String = """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:lang="en">
              <id>https://feed.eikowagenknecht.com/lootscraper/amazon</id>
              <title>Free Amazon Prime Games (PC)</title>
              <updated>2026-07-02T18:00:48.767Z</updated>
              <entry>
                <id>https://feed.eikowagenknecht.com/lootscraper/10498</id>
                <title>Amazon Prime (Game) - A Rat's Quest: The Way Back Home</title>
                <updated>2026-04-09T18:00:52.977Z</updated>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><ul><li><b>Offer valid from:</b> 2026-04-09 18:00</li><li><b>Offer valid to:</b> 2026-07-08 00:00</li><ul><li><b>Release date:</b> 2026-04-03</li><li><b>Description:</b> Follow Mat, the Scavenger, on his long journey home.</li><li><b>Genres:</b> Action, Adventure, Indie</li></ul></ul><p>Claim it now for free on <a href="https://luna.amazon.com/claims/a-rats-quest">Amazon Prime</a>.</p></div></content>
                <link href="https://luna.amazon.com/claims/a-rats-quest"/>
                <category term="Genre: Action" label="Action"/>
                <category term="Genre: Adventure" label="Adventure"/>
              </entry>
              <entry>
                <id>https://feed.eikowagenknecht.com/lootscraper/10512</id>
                <title>Amazon Prime (Game) - Mafia III: Definitive Edition</title>
                <updated>2026-06-25T17:00:49.584Z</updated>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><ul><li><b>Offer valid from:</b> 2026-06-25 17:00</li><li><b>Offer valid to:</b> 2026-09-23 00:00</li><ul><li><b>Description:</b> New Bordeaux, 1968. After your family is killed, you build a new one.</li><li><b>Genres:</b> Action</li></ul></ul><p>Claim it now for free on <a href="https://luna.amazon.com/claims/mafia-iii">Amazon Prime</a>.</p></div></content>
                <link href="https://luna.amazon.com/claims/mafia-iii"/>
                <category term="Genre: Action" label="Action"/>
              </entry>
            </feed>
        """.trimIndent()
    }
}
