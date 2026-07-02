package `in`.jphe.storyvox.source.rss

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.rss.config.RssConfig
import `in`.jphe.storyvox.source.rss.config.RssSubscription
import `in`.jphe.storyvox.source.rss.net.FetchResult
import `in`.jphe.storyvox.source.rss.net.RssFetcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #1489 — proves the in-memory parsed-feed cache in [RssSource].
 *
 * Before the fix, [RssSource.chapter] and [RssSource.fictionDetail] each
 * re-downloaded AND re-parsed the entire feed on every call, so tapping a
 * chapter sat on "Loading chapter…" for a full network round-trip. The
 * cache makes the first load warm the feed and every subsequent read
 * (within [RssSource.FEED_CACHE_TTL_MS]) instant.
 *
 * Why Robolectric: [fictionDetail]/[chapter] run the feed through
 * [RssParser], which uses `android.util.Xml.newPullParser()` (not on the
 * plain-JUnit classpath), and emit `android.util.Log` breadcrumbs. Same
 * runner the RDF fixture test uses. [RssFiltersTest] can skip it only
 * because its paths never parse.
 */
@RunWith(RobolectricTestRunner::class)
class RssFeedCacheTest {

    private val feedUrl = "https://example.com/feed.xml"
    private val fictionId = "rss:${feedUrl.hashCode()}"

    private val feedXml = """
        <rss version="2.0">
          <channel>
            <title>Test Feed</title>
            <item>
              <title>Chapter One</title>
              <guid>item-1</guid>
              <description>Body of chapter one.</description>
            </item>
            <item>
              <title>Chapter Two</title>
              <guid>item-2</guid>
              <description>Body of chapter two.</description>
            </item>
          </channel>
        </rss>
    """.trimIndent()

    private fun fakeConfig(subs: List<RssSubscription>) = object : RssConfig {
        private val state = MutableStateFlow(subs)
        override val subscriptions: Flow<List<RssSubscription>> = state.asStateFlow()
        override suspend fun snapshot(): List<RssSubscription> = state.value
        override suspend fun addFeed(url: String) {
            state.value = state.value + RssSubscription(fictionId = "rss:${url.hashCode()}", url = url)
        }
        override suspend fun removeFeed(fictionId: String) {
            state.value = state.value.filterNot { it.fictionId == fictionId }
        }
    }

    /**
     * Fake [RssFetcher] that never touches the network and counts how many
     * times [fetch] runs, so the tests can assert cache hits vs. misses.
     * Subclassing is why production [RssFetcher] / [RssFetcher.fetch] are
     * `open` (#1489).
     */
    private class CountingFetcher(private val xml: String) : RssFetcher(OkHttpClient()) {
        var fetchCount = 0
            private set

        override suspend fun fetch(
            url: String,
            previousEtag: String?,
            previousLastModified: String?,
        ): FictionResult<FetchResult> {
            fetchCount++
            return FictionResult.Success(FetchResult.Body(xml = xml, etag = null, lastModified = null))
        }
    }

    private fun sub() = RssSubscription(url = feedUrl, fictionId = fictionId)

    @Test
    fun `chapter tap after fictionDetail is served from cache — no second fetch`() = runTest {
        val fetcher = CountingFetcher(feedXml)
        // Constant clock: every read is inside the TTL window.
        val src = RssSource(fakeConfig(listOf(sub())), fetcher, now = { 0L })

        val detail = src.fictionDetail(fictionId) as FictionResult.Success
        assertEquals("fictionDetail triggers exactly one fetch", 1, fetcher.fetchCount)

        val chapterId = detail.value.chapters.first().id
        val chapter = src.chapter(fictionId, chapterId) as FictionResult.Success
        assertEquals("chapter() resolves the requested item", chapterId, chapter.value.info.id)
        // The core of #1489: the tap does NOT re-download / re-parse the feed.
        assertEquals("chapter tap served from cache", 1, fetcher.fetchCount)
    }

    @Test
    fun `repeated read within TTL is a cache hit`() = runTest {
        val fetcher = CountingFetcher(feedXml)
        var clockMs = 1_000L
        val src = RssSource(fakeConfig(listOf(sub())), fetcher, now = { clockMs })

        src.fictionDetail(fictionId)
        clockMs += RssSource.FEED_CACHE_TTL_MS - 1 // still inside the window
        src.fictionDetail(fictionId)

        assertEquals("second read within TTL must not re-fetch", 1, fetcher.fetchCount)
    }

    @Test
    fun `read after TTL expiry re-fetches`() = runTest {
        val fetcher = CountingFetcher(feedXml)
        var clockMs = 1_000L
        val src = RssSource(fakeConfig(listOf(sub())), fetcher, now = { clockMs })

        src.fictionDetail(fictionId)
        clockMs += RssSource.FEED_CACHE_TTL_MS + 1 // window elapsed
        src.fictionDetail(fictionId)

        assertEquals("expired cache entry must re-fetch", 2, fetcher.fetchCount)
    }
}
