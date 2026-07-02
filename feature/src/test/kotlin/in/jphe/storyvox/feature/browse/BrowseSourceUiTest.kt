package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plugin-seam Phase 3 (#384) — unit tests for the [BrowseSourceUi]
 * side-table.
 *
 * [BrowseSourceUi.supportedTabs] is still keyed by `SourceIds`, so it's
 * verified per in-tree backend. [BrowseSourceUi.chipLabel] and
 * [BrowseSourceUi.searchHint] moved their per-source strings onto the
 * `@SourcePlugin` descriptor in #1482; here they're only the
 * prefer-descriptor-else-displayName fallback, so those tests pin the
 * contract rather than enumerate every source.
 */
class BrowseSourceUiTest {

    // #1482 — chip labels moved onto the `@SourcePlugin` descriptor
    // (`chipLabel`). `BrowseSourceUi.chipLabel` is now the tiny
    // "prefer the descriptor value, else the formal displayName"
    // fallback; the per-source strings are asserted by each source's
    // annotation. These tests pin the fallback contract.

    @Test fun `chipLabel prefers the descriptor value over displayName`() {
        // e.g. AO3's formal displayName is "Archive of Our Own"; the
        // concise chip label rides @SourcePlugin(chipLabel = "AO3").
        assertEquals("AO3", BrowseSourceUi.chipLabel("AO3", "Archive of Our Own"))
    }

    @Test fun `chipLabel falls back to displayName when the descriptor label is blank`() {
        // Out-of-tree backend that didn't declare a chip label, or an
        // in-tree source whose chip == its formal name (e.g. "Radio").
        assertEquals("Custom Backend", BrowseSourceUi.chipLabel("", "Custom Backend"))
        // Whitespace-only is treated as "unset" too.
        assertEquals("Custom Backend", BrowseSourceUi.chipLabel("   ", "Custom Backend"))
    }

    @Test fun `supportedTabs gives a non-empty list for every in-tree source`() {
        for (id in IN_TREE_IDS) {
            val tabs = BrowseSourceUi.supportedTabs(id)
            assertTrue("Expected non-empty tabs for $id", tabs.isNotEmpty())
            assertTrue("Expected Popular tab for $id", BrowseTab.Popular in tabs)
        }
    }

    @Test fun `supportedTabs adds auth-only github tabs when signed in`() {
        val anon = BrowseSourceUi.supportedTabs(SourceIds.GITHUB, githubSignedIn = false)
        val authed = BrowseSourceUi.supportedTabs(SourceIds.GITHUB, githubSignedIn = true)

        assertFalse(BrowseTab.MyRepos in anon)
        assertFalse(BrowseTab.Starred in anon)
        assertFalse(BrowseTab.Gists in anon)
        assertTrue(BrowseTab.MyRepos in authed)
        assertTrue(BrowseTab.Starred in authed)
        assertTrue(BrowseTab.Gists in authed)
    }

    /**
     * Issue #695 — sources that declare `supportsSearch = false` on
     * their `@SourcePlugin` annotation (Slack, Telegram) used to fall
     * through to the default `Popular + Search` branch and surface a
     * Search tab that did nothing. The new `supportsSearch` parameter
     * strips Search from whatever branch matched.
     */
    @Test fun `supportedTabs strips Search when descriptor declares supportsSearch false`() {
        val withSearch = BrowseSourceUi.supportedTabs(SourceIds.SLACK, supportsSearch = true)
        val withoutSearch = BrowseSourceUi.supportedTabs(SourceIds.SLACK, supportsSearch = false)
        assertTrue(
            "Slack with supportsSearch=true should keep Search (legacy default)",
            BrowseTab.Search in withSearch,
        )
        assertFalse(
            "Slack with supportsSearch=false must not surface a Search tab",
            BrowseTab.Search in withoutSearch,
        )
        // Popular still present — only the Search tab is filtered.
        assertTrue(BrowseTab.Popular in withoutSearch)
    }

    /**
     * Issue #695 — defense-in-depth: even sources whose explicit branch
     * lists `Search` should honor the descriptor flag. Verifies the
     * filter runs after the per-source branch, not only on the `else`
     * fallthrough.
     */
    @Test fun `supportedTabs strips Search even from sources with an explicit branch`() {
        // Wikipedia has an explicit `Popular + Search` branch.
        val filtered = BrowseSourceUi.supportedTabs(SourceIds.WIKIPEDIA, supportsSearch = false)
        assertFalse(
            "Wikipedia branch lists Search explicitly; descriptor flag must still win",
            BrowseTab.Search in filtered,
        )
        assertTrue(BrowseTab.Popular in filtered)
    }

    /**
     * Issue #695 — the `supportsSearch` parameter defaults to `true`,
     * matching the pre-fix behavior for every caller that doesn't yet
     * thread the descriptor flag through.
     */
    @Test fun `supportedTabs default supportsSearch preserves legacy behavior`() {
        val withDefault = BrowseSourceUi.supportedTabs(SourceIds.WIKIPEDIA)
        val withExplicitTrue = BrowseSourceUi.supportedTabs(SourceIds.WIKIPEDIA, supportsSearch = true)
        assertEquals(withDefault, withExplicitTrue)
    }

    // #1482 — per-source search-empty-state copy moved onto the
    // `@SourcePlugin` descriptor (`searchHint`). `BrowseSourceUi.searchHint`
    // is now the "prefer the descriptor value, else Search <displayName>"
    // fallback. These tests pin that contract.

    @Test fun `searchHint prefers the descriptor value over the generic fallback`() {
        assertEquals(
            "Search AO3 by tag, fandom, or character",
            BrowseSourceUi.searchHint("Search AO3 by tag, fandom, or character", "Archive of Our Own"),
        )
    }

    @Test fun `searchHint falls back to Search displayName when the descriptor hint is blank`() {
        // Matches the old `else` branch — exactly what supportsSearch=false
        // sources (Slack, Telegram) surface, since they declare no hint.
        assertEquals("Search My Backend", BrowseSourceUi.searchHint("", "My Backend"))
        assertEquals("Search My Backend", BrowseSourceUi.searchHint("   ", "My Backend"))
    }

    private companion object {
        /** All in-tree backends. Adding a row here when a new backend
         *  lands is the explicit "did we add the UI hint?" audit step.
         *  Slack and Telegram were missing pre-#695 — adding them here
         *  surfaces any future drop-through-to-default bug for
         *  supportsSearch=false sources via the existing smoke loops. */
        val IN_TREE_IDS = setOf(
            SourceIds.ROYAL_ROAD, SourceIds.GITHUB, SourceIds.MEMPALACE,
            SourceIds.RSS, SourceIds.EPUB, SourceIds.OUTLINE,
            SourceIds.GUTENBERG, SourceIds.AO3, SourceIds.STANDARD_EBOOKS,
            SourceIds.WIKIPEDIA, SourceIds.WIKISOURCE,
            // Issue #417 — RADIO is canonical; KVMR alias kept for
            // one cycle to cover persisted-id resolution.
            SourceIds.RADIO, SourceIds.KVMR,
            // Issue #770 — split NOTION into TECHEMPOWER + PAT.
            SourceIds.NOTION_TECHEMPOWER, SourceIds.NOTION_PAT,
            SourceIds.HACKERNEWS, SourceIds.GOOGLE_NEWS, SourceIds.ARXIV,
            SourceIds.PLOS, SourceIds.DISCORD,
            // Issue #695 — Slack/Telegram declare supportsSearch=false;
            // the smoke loops verify their tab list still includes
            // Popular and isn't accidentally empty.
            SourceIds.SLACK, SourceIds.TELEGRAM,
        )
    }
}
