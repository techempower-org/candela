package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.source.googlenews.article.ArticleResolver
import `in`.jphe.storyvox.source.googlenews.net.GoogleNewsApi
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import `in`.jphe.storyvox.testkit.source.FictionSourceContractTest
import okhttp3.OkHttpClient

/**
 * #1491 — GoogleNews on the shared [FictionSourceContractTest].
 *
 * Google News's HTTP lives on the **detail/chapter** path: `popular()` returns a
 * static catalog (Browse cards) and `search()` a synthetic search fiction, so the
 * kit's list probe (which exercises `popular()`/`search()`) never touched the
 * network. Rather than change that deliberate design — or edit the kit — the probe
 * is bridged to the real network path with a thin **test-only adapter**:
 * `popular()` delegates to `fictionDetail(TOP_ID)`, which fetches + parses the
 * live feed. The new [GoogleNewsApi.baseUrl] seam points that fetch at the kit's
 * MockWebServer.
 *
 * The result: the Google News network path now gets the kit's IO-pin (#585),
 * 401 -> AuthRequired, 403-CF -> Cloudflare, and 429 -> RateLimited coverage that
 * the other HTTP sources have — with zero production-code behaviour change.
 */
class GoogleNewsContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        val api = object : GoogleNewsApi(client) {
            override val baseUrl: String get() = host
        }
        val real = GoogleNewsSource(api, NoOpArticleResolver)
        // Route the kit's list-exercise through the real HTTP path (fictionDetail).
        // Everything else delegates to the real source unchanged.
        return object : FictionSource by real {
            override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
                when (val d = real.fictionDetail(GoogleNewsSections.TOP_ID)) {
                    is FictionResult.Success ->
                        FictionResult.Success(ListPage(listOf(d.value.summary), page = 1, hasNext = false))
                    is FictionResult.Failure -> d
                }
        }
    }

    /** A minimal but real Google News RSS 2.0 body with one story — `fetchAndParse`
     *  treats an empty feed as NetworkError, so the happy path needs an item. */
    override fun happyListBody(): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<rss version="2.0"><channel><title>Top stories - Google News</title>""" +
            """<item><title>Sample headline</title>""" +
            """<link>https://news.google.com/articles/CBMiSample</link>""" +
            """<guid>guid-abc-123</guid></item></channel></rss>"""

    /** Every Google News feed path (top / topic / search) lives under `/rss`. */
    override fun listPathFragment(): String = "/rss"

    private object NoOpArticleResolver : ArticleResolver {
        override suspend fun resolve(item: GoogleNewsItem): String? = null
    }
}
