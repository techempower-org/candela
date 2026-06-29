package `in`.jphe.storyvox.source.googlenews.article

import `in`.jphe.storyvox.data.repository.GoogleNewsConfig
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import `in`.jphe.storyvox.source.readability.extract.ReadabilityExtractor
import `in`.jphe.storyvox.source.readability.net.ReadabilityFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1295 — the resolver's two behavioural guarantees, tested without
 * network: the opt-in gate (default OFF) and graceful degradation to the
 * digest when the link can't be decoded. The decode/fetch/extract internals
 * are covered by [GoogleNewsUrlDecoderTest] + on-device.
 */
class GoogleNewsArticleResolverTest {

    @Test
    fun `gate off returns null and never attempts a decode`() = runTest {
        val resolver = GoogleNewsArticleResolver(
            decoder = ExplodingDecoder(),
            fetcher = ReadabilityFetcher(OkHttpClient()),
            extractor = ReadabilityExtractor(),
            config = dagger.Lazy { FakeConfig(enabled = false) },
        )
        // ExplodingDecoder.decode() throws if reached — a pass proves the
        // flag short-circuits before any network work.
        assertNull(resolver.resolve(item()))
    }

    @Test
    fun `enabled but undecodable link degrades to null`() = runTest {
        val resolver = GoogleNewsArticleResolver(
            decoder = StubDecoder(decoded = null),
            fetcher = ReadabilityFetcher(OkHttpClient()),
            extractor = ReadabilityExtractor(),
            config = dagger.Lazy { FakeConfig(enabled = true) },
        )
        assertNull(resolver.resolve(item()))
    }

    // ── fakes ──────────────────────────────────────────────────────

    private fun item() = GoogleNewsItem(
        title = "Headline",
        publisher = "Example Times",
        link = "https://news.google.com/rss/articles/CBMiTEST?oc=5",
        guid = "guid-1",
        publishedAtEpochMs = null,
        relatedHeadlines = emptyList(),
    )

    private class FakeConfig(private val enabled: Boolean) : GoogleNewsConfig {
        override val fullArticleTextEnabled: Flow<Boolean> = flowOf(enabled)
        override suspend fun isFullArticleTextEnabled(): Boolean = enabled
    }

    private class StubDecoder(private val decoded: String?) :
        GoogleNewsUrlDecoder(OkHttpClient()) {
        override suspend fun decode(googleNewsUrl: String, locale: String): String? = decoded
    }

    private class ExplodingDecoder : GoogleNewsUrlDecoder(OkHttpClient()) {
        override suspend fun decode(googleNewsUrl: String, locale: String): String? =
            error("decode must not be called when the full-text flag is off")
    }
}
