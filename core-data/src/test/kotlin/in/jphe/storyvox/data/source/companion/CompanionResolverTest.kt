package `in`.jphe.storyvox.data.source.companion

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Issue #1208 — the cross-source audio↔text matcher. Exercised with a fake
 * [FictionSource] (the documented `:core-data` test pattern); only `id` +
 * `search` carry behaviour, the rest are inert stubs.
 */
class CompanionResolverTest {

    private class FakeSource(
        override val id: String,
        private val searchResults: List<FictionSummary> = emptyList(),
    ) : FictionSource {
        override val displayName: String get() = id
        var lastSearchTerm: String? = null

        override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
            lastSearchTerm = query.term
            return FictionResult.Success(ListPage(searchResults, page = 1, hasNext = false))
        }

        // Inert stubs — the matcher only calls search().
        override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
            FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        override suspend fun fictionDetail(fictionId: String) = FictionResult.NotFound("fake")
        override suspend fun chapter(fictionId: String, chapterId: String) = FictionResult.NotFound("fake")
        override suspend fun setFollowed(fictionId: String, followed: Boolean) = FictionResult.Success(Unit)
        override suspend fun genres() = FictionResult.Success(emptyList<String>())
    }

    private fun summary(
        id: String,
        sourceId: String,
        title: String = "Pride and Prejudice",
        companionSourceUrl: String? = null,
    ) = FictionSummary(
        id = id,
        sourceId = sourceId,
        title = title,
        author = "Jane Austen",
        companionSourceUrl = companionSourceUrl,
    )

    private fun resolver(vararg sources: FictionSource) =
        CompanionResolver(sources.associateBy { it.id })

    // ── forward: LibriVox audiobook → Gutenberg text ───────────────────

    @Test
    fun `librivox audiobook resolves to its gutenberg text via the back-link`() = runTest {
        val match = resolver(FakeSource(SourceIds.LIBRIVOX), FakeSource(SourceIds.GUTENBERG))
            .companionFor(summary("123", SourceIds.LIBRIVOX, companionSourceUrl = "https://www.gutenberg.org/ebooks/1342"))
        assertNotNull(match)
        assertEquals(SourceIds.GUTENBERG, match!!.sourceId)
        assertEquals("gutenberg:1342", match.fictionId)
        assertEquals(CompanionMatch.Kind.TEXT, match.kind)
    }

    @Test
    fun `librivox with no gutenberg back-link yields null`() = runTest {
        val r = resolver(FakeSource(SourceIds.LIBRIVOX), FakeSource(SourceIds.GUTENBERG))
        assertNull(r.companionFor(summary("123", SourceIds.LIBRIVOX, companionSourceUrl = null)))
        assertNull(r.companionFor(summary("123", SourceIds.LIBRIVOX, companionSourceUrl = "https://archive.org/x")))
    }

    @Test
    fun `text companion is suppressed when the gutenberg source is disabled`() = runTest {
        // No Gutenberg source in the map — deep-link would dead-end, so no offer.
        val match = resolver(FakeSource(SourceIds.LIBRIVOX))
            .companionFor(summary("123", SourceIds.LIBRIVOX, companionSourceUrl = "https://www.gutenberg.org/ebooks/1342"))
        assertNull(match)
    }

    // ── reverse: Gutenberg text → LibriVox audio (search + verify) ──────

    @Test
    fun `gutenberg text resolves to librivox audio, picking the back-link-verified candidate`() = runTest {
        val librivox = FakeSource(
            SourceIds.LIBRIVOX,
            searchResults = listOf(
                // Same title, WRONG ebook id — must be rejected despite ranking first.
                summary("999", SourceIds.LIBRIVOX, companionSourceUrl = "https://www.gutenberg.org/ebooks/9999"),
                // The true match, verified by exact back-link id.
                summary("42", SourceIds.LIBRIVOX, companionSourceUrl = "https://www.gutenberg.org/ebooks/1342"),
            ),
        )
        val match = resolver(librivox, FakeSource(SourceIds.GUTENBERG))
            .companionFor(summary("gutenberg:1342", SourceIds.GUTENBERG))
        assertNotNull(match)
        assertEquals(SourceIds.LIBRIVOX, match!!.sourceId)
        assertEquals("42", match.fictionId) // the verified one, not the first hit
        assertEquals(CompanionMatch.Kind.AUDIO, match.kind)
        assertEquals("Pride and Prejudice", librivox.lastSearchTerm) // searched by title
    }

    @Test
    fun `gutenberg text with no matching librivox recording yields null`() = runTest {
        val librivox = FakeSource(
            SourceIds.LIBRIVOX,
            searchResults = listOf(
                summary("999", SourceIds.LIBRIVOX, companionSourceUrl = "https://www.gutenberg.org/ebooks/9999"),
            ),
        )
        val match = resolver(librivox, FakeSource(SourceIds.GUTENBERG))
            .companionFor(summary("gutenberg:1342", SourceIds.GUTENBERG))
        assertNull(match)
    }

    @Test
    fun `non-pairable source yields null`() = runTest {
        val match = resolver(FakeSource(SourceIds.LIBRIVOX), FakeSource(SourceIds.GUTENBERG))
            .companionFor(summary("rr:1", SourceIds.ROYAL_ROAD))
        assertNull(match)
    }
}
