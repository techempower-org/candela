package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.repository.GoogleNewsEdition
import `in`.jphe.storyvox.data.repository.GoogleNewsPersonalFeed
import `in`.jphe.storyvox.data.repository.GoogleNewsTopic
import `in`.jphe.storyvox.data.repository.InMemoryGoogleNewsFeedStore
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
 * #1678 — the personalized "For you" section on the shared
 * [FictionSourceContractTest].
 *
 * "For you" is a fan-out (one fetch per followed topic / place / search,
 * merged), so it is a distinct network path from the single-feed one that
 * [GoogleNewsContractTest] pins. Same bridge technique: the kit's list probe
 * (`popular()`) is routed to `fictionDetail(FOR_YOU_ID)` with a Spanish
 * edition and one of each kind of followed section, so the fan-out gets the
 * kit's IO-pin (#585), 401 -> AuthRequired, CF detection and 429 ->
 * RateLimited coverage — i.e. an all-sections failure surfaces the mapped
 * error rather than an exception or an empty success.
 */
class GoogleNewsForYouContractTest : FictionSourceContractTest() {

    override fun createSource(client: OkHttpClient, baseUrl: String): FictionSource {
        val host = baseUrl.trimEnd('/')
        val api = object : GoogleNewsApi(client) {
            override val baseUrl: String get() = host
        }
        val store = InMemoryGoogleNewsFeedStore(
            GoogleNewsPersonalFeed(
                edition = GoogleNewsEdition.MX_ES,
                topics = listOf(GoogleNewsTopic.WORLD),
                locations = listOf("Ciudad de México"),
                searches = listOf("béisbol"),
            ),
        )
        val real = GoogleNewsSource(api, NoOpArticleResolver, store)
        return object : FictionSource by real {
            override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
                when (val d = real.fictionDetail(GoogleNewsSections.FOR_YOU_ID)) {
                    is FictionResult.Success ->
                        FictionResult.Success(ListPage(listOf(d.value.summary), page = 1, hasNext = false))
                    is FictionResult.Failure -> d
                }
        }
    }

    override fun happyListBody(): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<rss version="2.0"><channel><title>Para ti - Google Noticias</title>""" +
            """<item><title>Titular de ejemplo</title>""" +
            """<link>https://news.google.com/articles/CBMiSample</link>""" +
            """<guid>guid-es-1</guid></item></channel></rss>"""

    override fun listPathFragment(): String = "/rss"

    private object NoOpArticleResolver : ArticleResolver {
        override suspend fun resolve(item: GoogleNewsItem): String? = null
    }
}
