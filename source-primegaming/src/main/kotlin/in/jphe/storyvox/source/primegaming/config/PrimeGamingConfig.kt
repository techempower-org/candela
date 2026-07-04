package `in`.jphe.storyvox.source.primegaming.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1494 — read/write seam over the Prime Gaming source's single tunable:
 * **which feed URL to read the claim list from.**
 *
 * ## Why this is configurable (the single-maintainer mitigation)
 *
 * Amazon exposes no usable public API for Prime Gaming offers (the research
 * gate on #1494 confirmed: `gaming.amazon.com/graphql` → HTTP 403 unauth,
 * needs a paid Prime session + CSRF; scraping violates Amazon's Conditions of
 * Use). The validated data path is the community **LootScraper** project's
 * dedicated Amazon-Prime Atom feed (MIT, github.com/eikowagenknecht/lootscraper).
 *
 * That feed is a single volunteer's Cloudflare-fronted host — and it has
 * already migrated domains once (`feed.phenx.de` → `feed.eikowagenknecht.com`).
 * Hard-coding one URL would brick the source the day it moves again. So the URL
 * is persisted and overridable: [DEFAULT_FEED_URL] ships as the baked-in
 * default; a user (or a future settings row) can point it at a self-hosted
 * LootScraper instance or the feed's next home without an app update.
 *
 * ## Where the implementation lives (and the #1309 trap)
 *
 * The production implementation lives in `:app` (DataStore-backed), so this
 * source module stays free of Android Preferences plumbing — the same split as
 * [RssConfig][in.jphe.storyvox.source.rss.config.RssConfig] /
 * `RadioConfig` / `EpubConfig`. The consumer ([PrimeGamingApi]) injects this
 * behind a `dagger.Lazy` so the FictionSource → settings-config edge can never
 * form the Dagger initialization cycle that bit #1309 (which surfaces only at
 * `:app:hiltJavaCompileRelease`, never in a module build).
 */
interface PrimeGamingConfig {

    /** Hot stream of the effective feed URL — emits [DEFAULT_FEED_URL] until the
     *  user overrides it, then the override. Drives live-persist re-reads. */
    val feedUrlFlow: Flow<String>

    /** Synchronous snapshot of the effective feed URL (the override, or
     *  [DEFAULT_FEED_URL] when unset). Never blank. */
    suspend fun feedUrl(): String

    /** Persist an override. A null/blank value clears the override and reverts
     *  to [DEFAULT_FEED_URL] — so "reset to default" needs no separate call. */
    suspend fun setFeedUrl(url: String?)

    companion object {
        /**
         * Canonical LootScraper Amazon-Prime Atom feed (verified reachable with
         * Candela's honest UA on the #1494 research gate: HTTP 200, no throttle,
         * conditional-GET/304 supported, full per-entry narratable content).
         * The old `feed.phenx.de` host 301-redirects here; we bake the canonical
         * target so we don't pay a redirect hop on every poll.
         */
        const val DEFAULT_FEED_URL: String =
            "https://feed.eikowagenknecht.com/lootscraper_amazon_game.xml"
    }
}
