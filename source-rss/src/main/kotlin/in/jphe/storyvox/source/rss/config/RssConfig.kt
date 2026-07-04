package `in`.jphe.storyvox.source.rss.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #236 — abstraction over the RSS-source's persistent feed
 * list. The implementation lives in `:app` (DataStore) so the source
 * module stays free of Android Preferences plumbing; this interface
 * is what the source consumes.
 *
 * Each [RssSubscription] is one feed URL the user has added. The
 * fictionId for a feed in storyvox is derived from a stable hash of
 * the URL — the Hilt `Map<String, FictionSource>` keys on the
 * source id, then the per-fiction id keys on the URL hash so two
 * feeds added in different orders don't collide.
 */
interface RssConfig {
    /** Hot stream of the user's current feed list. */
    val subscriptions: Flow<List<RssSubscription>>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun snapshot(): List<RssSubscription>

    /** Add a feed by URL. No-op if the URL is already subscribed. */
    suspend fun addFeed(url: String)

    /** Remove a feed by its derived [RssSubscription.fictionId]. */
    suspend fun removeFeed(fictionId: String)

    /**
     * Issue #1498 — persist the feed URL autodiscovery resolved for a
     * subscription (e.g. the user pasted a bare homepage and the source
     * followed its `<link rel="alternate">` hint to the real feed). Stored
     * separately from the identity-bearing [RssSubscription.url] so the
     * URL-hash-derived [fictionId] never changes — rewriting `url` would
     * orphan every Room row keyed to the old id. Fetches then use
     * [RssSubscription.resolvedUrl] `?: url`. No-op if [fictionId] isn't
     * subscribed.
     *
     * Default is a no-op so hand-rolled test fakes need no change; the
     * production [RssConfigImpl] overrides it.
     */
    suspend fun setResolvedUrl(fictionId: String, resolvedUrl: String) {}
}

/**
 * One persisted feed subscription. [fictionId] is what storyvox uses
 * everywhere — it's a stable hash of [url], so the same feed has the same
 * id across re-launches.
 *
 * [resolvedUrl] (issue #1498) is the feed URL autodiscovery resolved from
 * [url] when they differ (bare-homepage paste → advertised feed). Null
 * until discovery runs; when set, fetches use it while [fictionId] stays
 * derived from the original [url] so no Room rows are orphaned.
 */
data class RssSubscription(
    val fictionId: String,
    val url: String,
    val resolvedUrl: String? = null,
)

/**
 * Issue #236 — derive the persistent fictionId from the URL. Stable
 * across re-launches; collision-resistant for the small N (a few
 * hundred feeds at most per user) that storyvox cares about. We
 * don't need cryptographic strength — `String.hashCode` plus a
 * length prefix is enough to keep IDs short and unambiguous.
 */
fun fictionIdForFeedUrl(url: String): String {
    val canonical = url.trim().lowercase()
    val hash = canonical.hashCode().toUInt().toString(16).padStart(8, '0')
    return "rss:$hash"
}
