package `in`.jphe.storyvox.source.handbook

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Candela Handbook read-path tests over a fake [CandelaHandbookReader] — the
 * :source-ocr OcrSourceTest pattern. No Robolectric, no Context, no assets: the
 * fake stands in for the bundled read so the source's mapping (manifest → the
 * single fiction, chapter id → ChapterContent) is verified in pure JVM.
 */
class CandelaHandbookSourceTest {

    private class FakeReader(
        private val manifest: HandbookManifest,
        private val bodies: Map<String, String> = emptyMap(),
    ) : CandelaHandbookReader {
        override suspend fun manifest(): HandbookManifest = manifest
        override suspend fun chapterText(id: String): String? = bodies[id]
    }

    private fun manifestOf(vararg titles: Pair<String, String>) =
        HandbookManifest(
            version = "1.10.0",
            chapters = titles.mapIndexed { i, (id, title) -> HandbookChapter(id, title, i) },
        )

    @Test
    fun `popular surfaces exactly one handbook fiction with the chapter count`() = runTest {
        val source = CandelaHandbookSource(
            FakeReader(manifestOf("getting-started" to "Getting Started", "voices" to "Voices")),
        )

        val result = source.popular(1)

        assertTrue(result is FictionResult.Success)
        val page = (result as FictionResult.Success).value
        assertEquals(1, page.items.size)
        assertFalse(page.hasNext)
        val book = page.items.single()
        assertEquals(CandelaHandbookSource.HANDBOOK_FICTION_ID, book.id)
        assertEquals("handbook", book.sourceId)
        assertEquals(2, book.chapterCount)
    }

    @Test
    fun `fictionDetail maps the manifest into ordered chapters`() = runTest {
        val source = CandelaHandbookSource(
            FakeReader(manifestOf("getting-started" to "Getting Started", "voices" to "Voices")),
        )

        val result = source.fictionDetail(CandelaHandbookSource.HANDBOOK_FICTION_ID)

        assertTrue(result is FictionResult.Success)
        val detail = (result as FictionResult.Success).value
        assertEquals(listOf("getting-started", "voices"), detail.chapters.map { it.id })
        assertEquals(listOf(0, 1), detail.chapters.map { it.index })
        assertEquals("Voices", detail.chapters[1].title)
    }

    @Test
    fun `chapter returns the body as plain text and escaped html`() = runTest {
        val source = CandelaHandbookSource(
            FakeReader(
                manifestOf("getting-started" to "Getting Started"),
                bodies = mapOf("getting-started" to "Tap Browse & pick a source.\n\nThen press play."),
            ),
        )

        val result = source.chapter(CandelaHandbookSource.HANDBOOK_FICTION_ID, "getting-started")

        assertTrue(result is FictionResult.Success)
        val content = (result as FictionResult.Success).value
        assertEquals("Tap Browse & pick a source.\n\nThen press play.", content.plainBody)
        // Two paragraphs, ampersand escaped, no raw markup leaking.
        assertTrue(content.htmlBody.contains("Tap Browse &amp; pick a source."))
        assertTrue(content.htmlBody.contains("<p>Then press play.</p>"))
        assertFalse(content.htmlBody.contains(" & "))
    }

    @Test
    fun `chapter for an unknown id is NotFound, not a crash`() = runTest {
        val source = CandelaHandbookSource(FakeReader(manifestOf("voices" to "Voices")))

        val missing = source.chapter(CandelaHandbookSource.HANDBOOK_FICTION_ID, "nope")
        val wrongBook = source.fictionDetail("some-other-book")

        assertTrue(missing is FictionResult.NotFound)
        assertTrue(wrongBook is FictionResult.NotFound)
    }

    @Test
    fun `search matches the handbook by keyword and empties on a miss`() = runTest {
        val source = CandelaHandbookSource(FakeReader(manifestOf("voices" to "Voices")))

        val hit = source.search(query("handbook"))
        val blank = source.search(query(""))
        val miss = source.search(query("dragons"))

        assertEquals(1, ((hit as FictionResult.Success).value).items.size)
        assertEquals(1, ((blank as FictionResult.Success).value).items.size)
        assertTrue(((miss as FictionResult.Success).value).items.isEmpty())
    }

    @Test
    fun `handbook cannot be followed`() = runTest {
        val source = CandelaHandbookSource(FakeReader(manifestOf("voices" to "Voices")))

        assertNotNull(source.followsList(1))
        assertTrue(source.followsList(1) is FictionResult.Success)
        assertTrue(source.setFollowed(CandelaHandbookSource.HANDBOOK_FICTION_ID, true) is FictionResult.NotFound)
    }

    private fun query(term: String) =
        `in`.jphe.storyvox.data.source.model.SearchQuery(term = term)
}
