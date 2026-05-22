package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.feature.api.GenericBrowseFilter
import `in`.jphe.storyvox.feature.api.GenericDateRange
import `in`.jphe.storyvox.feature.api.GenericSortOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #693 — covers the generic filter active/count logic and the
 * per-source capability rows. The capability table is the contract
 * the sheet relies on to decide which knobs to render — if these
 * tests change shape, expect a phone-side audit pass.
 */
class GenericBrowseFilterTest {

    @Test fun `default filter is inactive`() {
        assertFalse(GenericBrowseFilter().isActive())
        assertEquals(0, GenericBrowseFilter().activeCount())
    }

    @Test fun `sortOrder counts as active when non-default`() {
        val f = GenericBrowseFilter(sortOrder = GenericSortOrder.Newest)
        assertTrue(f.isActive())
        assertEquals(1, f.activeCount())
    }

    @Test fun `language counts as active when non-blank`() {
        val f = GenericBrowseFilter(language = "en")
        assertTrue(f.isActive())
        assertEquals(1, f.activeCount())
        // Blank language is treated as no filter.
        assertFalse(GenericBrowseFilter(language = "").isActive())
        assertFalse(GenericBrowseFilter(language = "  ").isActive())
    }

    @Test fun `category counts as active when non-blank`() {
        val f = GenericBrowseFilter(category = "Fantasy")
        assertTrue(f.isActive())
        assertEquals(1, f.activeCount())
    }

    @Test fun `dateRange counts as active when non-Any`() {
        val f = GenericBrowseFilter(dateRange = GenericDateRange.Last30Days)
        assertTrue(f.isActive())
        assertEquals(1, f.activeCount())
    }

    @Test fun `multiple active knobs sum up`() {
        val f = GenericBrowseFilter(
            sortOrder = GenericSortOrder.Popular,
            language = "fr",
            category = "Horror",
            dateRange = GenericDateRange.Last7Days,
        )
        assertEquals(4, f.activeCount())
    }

    @Test fun `Gutenberg capabilities expose category + language + sort`() {
        val caps = BrowseSourceUi.genericCapabilities(SourceIds.GUTENBERG)
        assertTrue(caps.supportsCategory)
        assertTrue(caps.supportsLanguage)
        assertTrue(caps.supportsSortOrder)
        assertFalse(caps.supportsDateRange)
        assertTrue(caps.availableCategories.isNotEmpty())
        assertTrue(caps.availableLanguages.contains("en"))
    }

    @Test fun `arXiv capabilities expose category + dateRange but not language`() {
        val caps = BrowseSourceUi.genericCapabilities(SourceIds.ARXIV)
        assertTrue(caps.supportsCategory)
        assertTrue(caps.supportsDateRange)
        assertFalse(caps.supportsLanguage)
        // arXiv categories include the curated CS subset.
        assertTrue(caps.availableCategories.contains("cs.AI"))
    }

    @Test fun `HackerNews capabilities expose category sort dateRange but not language`() {
        val caps = BrowseSourceUi.genericCapabilities(SourceIds.HACKERNEWS)
        assertTrue(caps.supportsCategory)
        assertTrue(caps.supportsSortOrder)
        assertTrue(caps.supportsDateRange)
        assertFalse(caps.supportsLanguage)
    }

    @Test fun `Wikipedia capabilities expose only language`() {
        val caps = BrowseSourceUi.genericCapabilities(SourceIds.WIKIPEDIA)
        assertTrue(caps.supportsLanguage)
        assertFalse(caps.supportsCategory)
        assertFalse(caps.supportsSortOrder)
        assertFalse(caps.supportsDateRange)
    }

    @Test fun `RSS capabilities expose sort + dateRange`() {
        val caps = BrowseSourceUi.genericCapabilities(SourceIds.RSS)
        assertTrue(caps.supportsSortOrder)
        assertTrue(caps.supportsDateRange)
        assertFalse(caps.supportsLanguage)
        assertFalse(caps.supportsCategory)
    }

    @Test fun `default capabilities are all-off for unknown sources`() {
        val caps = BrowseSourceUi.genericCapabilities(SourceIds.EPUB)
        assertFalse(caps.supportsCategory)
        assertFalse(caps.supportsLanguage)
        assertFalse(caps.supportsSortOrder)
        assertFalse(caps.supportsDateRange)
    }
}
