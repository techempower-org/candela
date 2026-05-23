package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.5.76 — pins the favorites-first ordering invariant from
 * [BrowseViewModel]'s `controls` flow: favorites pinned to the
 * front of the visible-sources list in registry order, non-
 * favorites follow in registry order. Tested as a pure function
 * so no Hilt or Compose host is needed.
 */
class BrowseViewModelFavoritesOrderingTest {

    private val rr = descriptor("rr", "Royal Road")
    private val gh = descriptor("github", "GitHub")
    private val ao3 = descriptor("ao3", "AO3")
    private val gut = descriptor("gutenberg", "Gutenberg")
    private val wiki = descriptor("wikipedia", "Wikipedia")

    private val registry = listOf(rr, gh, ao3, gut, wiki)

    @Test fun `no favorites preserves registry order`() {
        val result = visibleSources(registry, enabled = setOf("rr", "github", "ao3", "gutenberg", "wikipedia"), favorites = emptySet())
        assertEquals(listOf("rr", "github", "ao3", "gutenberg", "wikipedia"), result.map { it.id })
    }

    @Test fun `single favorite pins to front`() {
        val result = visibleSources(registry, enabled = setOf("rr", "github", "ao3", "gutenberg", "wikipedia"), favorites = setOf("ao3"))
        assertEquals(listOf("ao3", "rr", "github", "gutenberg", "wikipedia"), result.map { it.id })
    }

    @Test fun `multiple favorites preserve registry order among themselves`() {
        val result = visibleSources(registry, enabled = setOf("rr", "github", "ao3", "gutenberg", "wikipedia"), favorites = setOf("gutenberg", "github"))
        assertEquals(listOf("github", "gutenberg", "rr", "ao3", "wikipedia"), result.map { it.id })
    }

    @Test fun `disabled sources are excluded even if favorited`() {
        val result = visibleSources(registry, enabled = setOf("rr", "ao3", "wikipedia"), favorites = setOf("github", "ao3"))
        assertEquals(listOf("ao3", "rr", "wikipedia"), result.map { it.id })
    }

    @Test fun `all favorited yields full registry order`() {
        val all = registry.map { it.id }.toSet()
        val result = visibleSources(registry, enabled = all, favorites = all)
        assertEquals(listOf("rr", "github", "ao3", "gutenberg", "wikipedia"), result.map { it.id })
    }

    @Test fun `empty enabled yields empty list`() {
        val result = visibleSources(registry, enabled = emptySet(), favorites = setOf("rr"))
        assertEquals(emptyList<String>(), result.map { it.id })
    }

    /**
     * Mirrors the partition logic from [BrowseViewModel]'s `controls`
     * combine block (lines ~364-371). Extracted here so the test
     * doesn't need the full ViewModel.
     */
    private fun visibleSources(
        descriptors: List<SourcePluginDescriptor>,
        enabled: Set<String>,
        favorites: Set<String>,
    ): List<SourcePluginDescriptor> {
        val faves = mutableListOf<SourcePluginDescriptor>()
        val rest = mutableListOf<SourcePluginDescriptor>()
        for (d in descriptors) {
            if (d.id !in enabled) continue
            if (d.id in favorites) faves.add(d) else rest.add(d)
        }
        return faves + rest
    }

    private fun descriptor(id: String, name: String) = SourcePluginDescriptor(
        id = id,
        displayName = name,
        defaultEnabled = false,
        category = SourceCategory.Text,
        supportsFollow = false,
        supportsSearch = false,
        source = FakeFictionSource(id),
    )

    private class FakeFictionSource(override val id: String) : FictionSource {
        override val displayName: String = id
        override suspend fun popular(page: Int) =
            FictionResult.Success(ListPage<FictionSummary>(items = emptyList(), page = page, hasNext = false))
        override suspend fun latestUpdates(page: Int) = popular(page)
        override suspend fun byGenre(genre: String, page: Int) = popular(page)
        override suspend fun search(query: SearchQuery) = popular(1)
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = FictionResult.NotFound("fake")
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = FictionResult.NotFound("fake")
        override suspend fun followsList(page: Int) = popular(page)
        override suspend fun setFollowed(fictionId: String, followed: Boolean) = FictionResult.Success(Unit)
        override suspend fun genres() = FictionResult.Success(emptyList<String>())
    }
}
