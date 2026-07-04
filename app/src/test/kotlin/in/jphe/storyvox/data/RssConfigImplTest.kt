package `in`.jphe.storyvox.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import `in`.jphe.storyvox.source.rss.config.fictionIdForFeedUrl
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Issue #1498 — [RssConfigImpl] persistence of the autodiscovered feed URL
 * (`resolvedUrl`) as a field distinct from the identity-bearing
 * subscription URL. Real temp-file DataStore so serialization is identical
 * to production — same shape as the [SettingsRepositoryUiImpl] tests.
 *
 * Guarantees under test:
 *  - `resolvedUrl` round-trips through the pipe/space record format;
 *  - the `fictionId` stays derived from the original `url` (never the
 *    resolved URL) so no Room rows are orphaned;
 *  - legacy pipe-only-URL data decodes with `resolvedUrl = null`;
 *  - `removeFeed` drops the resolved record with the subscription.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RssConfigImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: DataStore<Preferences>
    private lateinit var config: RssConfigImpl

    private val feedsKey = stringPreferencesKey("pref_rss_feeds")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher())
        val file = File(tempFolder.newFolder(), "storyvox_rss.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
        config = RssConfigImpl(store)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test fun `a freshly added feed has a null resolvedUrl`() = runTest {
        config.addFeed("https://tricycle.org/feed")
        val sub = config.snapshot().single()
        assertEquals("https://tricycle.org/feed", sub.url)
        assertNull("no discovery has run yet", sub.resolvedUrl)
        assertEquals(fictionIdForFeedUrl("https://tricycle.org/feed"), sub.fictionId)
    }

    @Test fun `setResolvedUrl persists the discovered feed without changing identity`() = runTest {
        config.addFeed("https://tricycle.org")
        val fid = fictionIdForFeedUrl("https://tricycle.org")

        config.setResolvedUrl(fid, "https://tricycle.org/feed/atom/")

        val sub = config.snapshot().single()
        assertEquals("identity URL unchanged", "https://tricycle.org", sub.url)
        assertEquals("fetch URL persisted", "https://tricycle.org/feed/atom/", sub.resolvedUrl)
        // The critical invariant: fictionId still derives from the original URL.
        assertEquals(fid, sub.fictionId)
        assertEquals(fictionIdForFeedUrl("https://tricycle.org"), sub.fictionId)
    }

    @Test fun `resolvedUrl survives a fresh config instance (persistence)`() = runTest {
        config.addFeed("https://tricycle.org")
        val fid = fictionIdForFeedUrl("https://tricycle.org")
        config.setResolvedUrl(fid, "https://tricycle.org/feed/atom/")

        // A new RssConfigImpl over the SAME store models a process relaunch.
        val reopened = RssConfigImpl(store)
        val sub = reopened.snapshot().single()
        assertEquals("https://tricycle.org/feed/atom/", sub.resolvedUrl)
        assertEquals("https://tricycle.org", sub.url)
    }

    @Test fun `legacy pipe-only URL data decodes with null resolvedUrl`() = runTest {
        // Pre-#1498 on-disk format: bare pipe-separated URLs, no resolved URL.
        store.edit { it[feedsKey] = "https://a.example.com/feed|https://b.example.com/feed" }

        val subs = config.snapshot()
        assertEquals(2, subs.size)
        subs.forEach { assertNull("upgrade re-discovers once", it.resolvedUrl) }
        assertEquals("https://a.example.com/feed", subs[0].url)
        assertEquals("https://b.example.com/feed", subs[1].url)
        assertEquals(fictionIdForFeedUrl("https://b.example.com/feed"), subs[1].fictionId)
    }

    @Test fun `setResolvedUrl is a no-op for an unknown fictionId`() = runTest {
        config.addFeed("https://a.example.com/feed")
        config.setResolvedUrl("rss:deadbeef", "https://evil.example.com/feed")
        assertNull(config.snapshot().single().resolvedUrl)
    }

    @Test fun `setResolvedUrl does not persist when resolved equals the identity URL`() = runTest {
        config.addFeed("https://a.example.com/feed")
        val fid = fictionIdForFeedUrl("https://a.example.com/feed")
        config.setResolvedUrl(fid, "https://a.example.com/feed")
        // Nothing to resolve — record stays legacy-shaped, resolvedUrl null.
        assertNull(config.snapshot().single().resolvedUrl)
    }

    @Test fun `setResolvedUrl back to the identity URL clears a stale resolvedUrl`() = runTest {
        // #1549 review — resetting a subscription to its identity URL must
        // clear a previously-set resolvedUrl, not leave the stale one in place.
        config.addFeed("https://tricycle.org")
        val fid = fictionIdForFeedUrl("https://tricycle.org")
        config.setResolvedUrl(fid, "https://tricycle.org/feed/atom/")
        assertEquals("https://tricycle.org/feed/atom/", config.snapshot().single().resolvedUrl)

        config.setResolvedUrl(fid, "https://tricycle.org") // == identity URL

        assertNull("stale resolvedUrl cleared", config.snapshot().single().resolvedUrl)
    }

    @Test fun `removeFeed drops the resolved record too`() = runTest {
        config.addFeed("https://tricycle.org")
        val fid = fictionIdForFeedUrl("https://tricycle.org")
        config.setResolvedUrl(fid, "https://tricycle.org/feed/atom/")

        config.removeFeed(fid)

        assertEquals(emptyList<Any>(), config.snapshot())
    }

    @Test fun `resolvedUrl round-trips alongside a plain sibling subscription`() = runTest {
        config.addFeed("https://tricycle.org")   // will get a resolvedUrl
        config.addFeed("https://plain.example.com/feed") // stays plain
        val fid = fictionIdForFeedUrl("https://tricycle.org")
        config.setResolvedUrl(fid, "https://tricycle.org/feed/atom/")

        val subs = config.snapshot()
        assertEquals(2, subs.size)
        val resolvedSub = subs.first { it.fictionId == fid }
        val plainSub = subs.first { it.url == "https://plain.example.com/feed" }
        assertEquals("https://tricycle.org/feed/atom/", resolvedSub.resolvedUrl)
        assertNull(plainSub.resolvedUrl)
    }

    @Test fun `flow emits the persisted resolvedUrl`() = runTest {
        config.addFeed("https://tricycle.org")
        val fid = fictionIdForFeedUrl("https://tricycle.org")
        config.setResolvedUrl(fid, "https://tricycle.org/feed/atom/")

        val subs = config.subscriptions.first()
        assertEquals("https://tricycle.org/feed/atom/", subs.single().resolvedUrl)
    }
}
