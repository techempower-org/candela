package `in`.jphe.storyvox.feature.browse.catalog

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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source Catalog (#1365) — unit tests for the pure transformation layer
 * of the catalog view-model: the search filter ([filterCatalog]), the
 * category→group mapping ([catalogGroupOf]), and the section grouping
 * ([groupCatalog]). Plain JUnit, no Compose host — the rendering is a
 * thin shell over these functions.
 */
class SourceCatalogLogicTest {

    private val books = row("gutenberg", "Project Gutenberg", "Public-domain classics", SourceCategory.Ebook)
    private val ebook2 = row("standard-ebooks", "Standard Ebooks", "Hand-crafted editions", SourceCategory.Ebook)
    private val text = row("royalroad", "Royal Road", "Web serials & litRPG", SourceCategory.Text)
    private val text2 = row("wikipedia", "Wikipedia", "Encyclopedia articles", SourceCategory.Text)
    private val audio = row("radio", "Radio", "Community radio streams", SourceCategory.AudioStream)
    private val db = row("notion", "Notion", "Structured database pages", SourceCategory.Database)
    private val other = row("readability", "Reader", "Any web article", SourceCategory.Other)

    private val all = listOf(books, ebook2, text, text2, audio, db, other)

    // ─── filterCatalog ───────────────────────────────────────────────

    @Test fun `blank query keeps everything`() {
        assertEquals(all, filterCatalog(all, "   "))
    }

    @Test fun `filter matches display name case-insensitively`() {
        val out = filterCatalog(all, "ROYAL")
        assertEquals(listOf("royalroad"), out.map { it.descriptor.id })
    }

    @Test fun `filter matches description substring`() {
        val out = filterCatalog(all, "radio")
        assertEquals(listOf("radio"), out.map { it.descriptor.id })
    }

    @Test fun `filter matches plugin id`() {
        val out = filterCatalog(all, "wikipedia")
        assertEquals(listOf("wikipedia"), out.map { it.descriptor.id })
    }

    @Test fun `filter with no match returns empty`() {
        assertTrue(filterCatalog(all, "zzz-nonexistent").isEmpty())
    }

    // ─── catalogGroupOf ──────────────────────────────────────────────

    @Test fun `category maps to catalog group`() {
        assertEquals(CatalogGroup.Books, catalogGroupOf(SourceCategory.Ebook))
        assertEquals(CatalogGroup.Text, catalogGroupOf(SourceCategory.Text))
        assertEquals(CatalogGroup.Audio, catalogGroupOf(SourceCategory.AudioStream))
        assertEquals(CatalogGroup.Other, catalogGroupOf(SourceCategory.Other))
    }

    @Test fun `Database folds into Other`() {
        assertEquals(CatalogGroup.Other, catalogGroupOf(SourceCategory.Database))
    }

    // ─── groupCatalog ────────────────────────────────────────────────

    @Test fun `grouping buckets sources by category in display order`() {
        val sections = groupCatalog(all)
        assertEquals(
            listOf(CatalogGroup.Books, CatalogGroup.Text, CatalogGroup.Audio, CatalogGroup.Other),
            sections.map { it.group },
        )
    }

    @Test fun `grouping preserves input order within a section`() {
        val sections = groupCatalog(all)
        val booksSection = sections.first { it.group == CatalogGroup.Books }
        assertEquals(listOf("gutenberg", "standard-ebooks"), booksSection.rows.map { it.descriptor.id })
    }

    @Test fun `grouping folds Database and Other into the Other section`() {
        val sections = groupCatalog(all)
        val otherSection = sections.first { it.group == CatalogGroup.Other }
        assertEquals(listOf("notion", "readability"), otherSection.rows.map { it.descriptor.id })
    }

    @Test fun `grouping drops empty sections`() {
        // Only Text sources — Books / Audio / Other sections must not appear.
        val sections = groupCatalog(listOf(text, text2))
        assertEquals(listOf(CatalogGroup.Text), sections.map { it.group })
    }

    @Test fun `grouping empty input yields no sections`() {
        assertTrue(groupCatalog(emptyList()).isEmpty())
    }

    @Test fun `filter then group composes - audio query yields only the Audio section`() {
        val sections = groupCatalog(filterCatalog(all, "radio"))
        assertEquals(listOf(CatalogGroup.Audio), sections.map { it.group })
        assertEquals(listOf("radio"), sections.single().rows.map { it.descriptor.id })
    }

    // ─── fixtures ────────────────────────────────────────────────────

    private fun row(
        id: String,
        displayName: String,
        description: String,
        category: SourceCategory,
        enabled: Boolean = true,
    ): SourceCatalogRow = SourceCatalogRow(
        descriptor = SourcePluginDescriptor(
            id = id,
            displayName = displayName,
            defaultEnabled = enabled,
            category = category,
            supportsFollow = false,
            supportsSearch = true,
            description = description,
            sourceUrl = "",
            source = FakeFictionSource(id),
        ),
        enabled = enabled,
    )

    private class FakeFictionSource(override val id: String) : FictionSource {
        override val displayName: String = id
        override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> = popular(1)
        override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = FictionResult.NotFound("fake")
        override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = FictionResult.NotFound("fake")
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = popular(page)
        override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> = FictionResult.Success(Unit)
        override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())
    }
}
