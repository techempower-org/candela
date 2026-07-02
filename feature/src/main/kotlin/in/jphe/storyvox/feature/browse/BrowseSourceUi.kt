package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds

/**
 * Plugin-seam Phase 3 (#384) — side-table of per-source UI hints that
 * are genuinely Compose-shaped. Lives in `:feature/browse` because
 * these bits can't live on the `SourcePluginDescriptor` (which sits in
 * `:core-data` and shouldn't know about `BrowseTab` or filter shapes).
 *
 * The Phase 1/2 `BrowseSourceKey` enum carried this data inline on
 * each enum entry. Phase 3 deleted the enum in favour of iterating
 * `SourcePluginRegistry.descriptors`.
 *
 * #1482 — chip labels and search-empty-state hints, which *are*
 * plain-string UI copy, moved onto the `@SourcePlugin` descriptor
 * ([chipLabel] / [searchHint] now just prefer the descriptor field and
 * fall back to `displayName`). What remains keyed by `SourceIds` here
 * is [supportedTabs] — the one hint whose shape (`List<BrowseTab>`,
 * auth gating) can't be expressed on a `:core-data` descriptor. A
 * future out-of-tree backend gets its chip label / search hint from
 * its own annotation for free, and only needs a [supportedTabs] row if
 * it wants more than the default Popular + Search.
 */
internal object BrowseSourceUi {

    /**
     * Short label for the browse chip strip. Prefers the source's
     * `@SourcePlugin(chipLabel = …)` — carried on the descriptor and
     * passed in as [descriptorChipLabel] — because the registry's
     * `displayName` is the formal "Archive of Our Own" / "Local EPUB
     * files" form, which doesn't fit on a chip on a narrow phone. Falls
     * back to [displayName] when the plugin didn't declare a chip label.
     *
     * #1482 — replaces the per-source `when`-branch (deferred step 4 of
     * #1400). The concise labels now live on each source's
     * `@SourcePlugin` annotation and ride the descriptor, so an
     * out-of-tree backend can declare its own chip label without a
     * `:feature` edit. Notable in-tree overrides that used to live in
     * the branch: "Memory Palace" → "Palace" (#148, avoids a two-line
     * chip), "Local EPUB files" → "Local" / "Local PDF files" → "PDFs"
     * (#996, keeps the two local-file backends separable), and
     * "GitHub fiction" → "GitHub".
     */
    fun chipLabel(descriptorChipLabel: String, displayName: String): String =
        descriptorChipLabel.ifBlank { displayName }

    /**
     * Tabs meaningful for [id]. [githubSignedIn] gates the auth-only
     * GitHub tabs (`MyRepos` #200, `Starred` #201, `Gists` #202).
     * [ao3SignedIn] gates the auth-only AO3 tabs
     * (`Ao3MySubscriptions` / `Ao3MarkedForLater`, #426 PR2).
     * Unknown ids fall through to a reasonable default of
     * Popular + Search.
     *
     * Issue #695 — when [supportsSearch] is `false`, the
     * [BrowseTab.Search] tab is stripped out of whichever branch
     * matched. Sources that declare `supportsSearch = false` on their
     * `@SourcePlugin` annotation (Slack, Telegram) used to hit the
     * `else` fallthrough below and surface a Search tab that did
     * nothing — typing a query returned `empty ListPage` and the user
     * concluded the source was broken. Filtering here is the
     * single-source-of-truth fix; the per-source branches stay as
     * "ideal default tab set" and the descriptor's annotation wins
     * when it disagrees. Defaulting to `true` keeps every existing
     * caller and test path unchanged.
     */
    fun supportedTabs(
        id: String,
        githubSignedIn: Boolean = false,
        ao3SignedIn: Boolean = false,
        supportsSearch: Boolean = true,
    ): List<BrowseTab> {
        val tabs = supportedTabsRaw(id, githubSignedIn, ao3SignedIn)
        return if (supportsSearch) tabs else tabs.filterNot { it == BrowseTab.Search }
    }

    private fun supportedTabsRaw(
        id: String,
        githubSignedIn: Boolean,
        ao3SignedIn: Boolean,
    ): List<BrowseTab> = when (id) {
        SourceIds.ROYAL_ROAD -> listOf(
            BrowseTab.Popular,
            BrowseTab.NewReleases,
            BrowseTab.BestRated,
            BrowseTab.Search,
        )
        SourceIds.GITHUB -> buildList {
            add(BrowseTab.Popular)
            add(BrowseTab.NewReleases)
            if (githubSignedIn) {
                add(BrowseTab.MyRepos)
                add(BrowseTab.Starred)
                add(BrowseTab.Gists)
            }
            add(BrowseTab.Search)
        }
        SourceIds.MEMPALACE -> listOf(BrowseTab.Popular, BrowseTab.NewReleases)
        SourceIds.RSS -> listOf(BrowseTab.NewReleases, BrowseTab.Popular, BrowseTab.Search)
        SourceIds.EPUB -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.PDF -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.OUTLINE -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.GUTENBERG -> listOf(BrowseTab.Popular, BrowseTab.NewReleases, BrowseTab.Search)
        // Issue #445 — AO3 declared `supportsSearch = true` on its
        // plugin manifest but the tab list omitted BrowseTab.Search,
        // so the icon disappeared on the chip → users had no way to
        // search a 50-deep Popular feed and concluded the capability
        // was missing. The underlying Ao3Source.search() returns empty
        // for v0.5.40 (the feed-keyed catalog doesn't expose a search
        // endpoint AO3 will accept anonymously), but adding the tab
        // surface keeps the chip-row consistent across sources that
        // advertise search. The Search tab's empty results will be
        // replaced by a proper AO3 search implementation in a follow-up.
        //
        // #426 PR2 — when the user has signed in to AO3, the chip
        // strip gains "My Subscriptions" and "Marked for Later"
        // entries. Signed-out users see the same 3-tab strip we
        // shipped pre-#426.
        SourceIds.AO3 -> buildList {
            add(BrowseTab.Popular)
            add(BrowseTab.NewReleases)
            if (ao3SignedIn) {
                add(BrowseTab.Ao3MySubscriptions)
                add(BrowseTab.Ao3MarkedForLater)
            }
            add(BrowseTab.Search)
        }
        SourceIds.STANDARD_EBOOKS -> listOf(BrowseTab.Popular, BrowseTab.NewReleases, BrowseTab.Search)
        // #796 — On This Day + In the News surface the same
        // `feed/featured` clusters the Popular tab already fetches;
        // always visible (no auth gate).
        SourceIds.WIKIPEDIA -> listOf(
            BrowseTab.Popular,
            BrowseTab.WikipediaOnThisDay,
            BrowseTab.WikipediaInTheNews,
            BrowseTab.Search,
        )
        SourceIds.WIKISOURCE -> listOf(BrowseTab.Popular, BrowseTab.Search)
        // Issue #417 — Radio gains a Search tab (Radio Browser API).
        // The legacy KVMR alias keeps Popular-only because v0.5.20+
        // persisted resolutions through the alias never expected a
        // Search tab anyway.
        SourceIds.RADIO -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.KVMR -> listOf(BrowseTab.Popular)
        SourceIds.NOTION_TECHEMPOWER -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.NOTION_PAT -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.HACKERNEWS -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.ARXIV -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.PLOS -> listOf(BrowseTab.Popular, BrowseTab.Search)
        SourceIds.DISCORD -> listOf(BrowseTab.Popular, BrowseTab.Search)
        else -> listOf(BrowseTab.Popular, BrowseTab.Search)
    }

    /**
     * Issue #271 — per-source subtitle for the Search empty state.
     *
     * #1482 — sourced from the source's `@SourcePlugin(searchHint = …)`
     * (carried on `SourcePluginDescriptor.searchHint`) instead of a
     * per-source `when`-branch (deferred step 4 of #1400). Falls back to
     * "Search &lt;displayName&gt;" when the plugin didn't declare a
     * hint — matching the old `else` branch, which is exactly what
     * `supportsSearch = false` sources (Slack, Telegram) surfaced.
     */
    fun searchHint(descriptorSearchHint: String, displayName: String): String =
        descriptorSearchHint.ifBlank { "Search $displayName" }
}

