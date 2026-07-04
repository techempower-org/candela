package `in`.jphe.storyvox.feature.docs

import `in`.jphe.storyvox.data.docs.DocPdfExporter
import `in`.jphe.storyvox.data.docs.DocPdfRequest
import `in`.jphe.storyvox.data.docs.DocPdfResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Issue #1513 — DocScanViewModel logic over a fake exporter (no
 * PdfDocument, no ContentResolver, no Robolectric). Verifies capture
 * accumulation, list-based reorder/delete, and the export → share-signal
 * flow including the failure path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocScanViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeExporter(var next: DocPdfResult) : DocPdfExporter {
        var calls = 0
        var lastRequest: DocPdfRequest? = null
        override suspend fun exportToPdf(request: DocPdfRequest): DocPdfResult {
            calls++
            lastRequest = request
            return next
        }
    }

    private fun ok() = DocPdfResult.Success(
        filePath = "/cache/exports/Doc.pdf",
        fileName = "Doc.pdf",
        pageCount = 2,
        byteSize = 245_000L,
    )

    @Test
    fun `captured pages accumulate in order`() = runTest(dispatcher) {
        val vm = DocScanViewModel(FakeExporter(ok()))

        vm.onPagesCaptured(listOf("content://a", "content://b"))
        vm.onPagesCaptured(listOf("content://c"))

        val pages = vm.state.value.pages
        assertEquals(listOf("content://a", "content://b", "content://c"), pages.map { it.uri })
        // Ids are unique + monotonic so reorder/delete key stably.
        assertEquals(3, pages.map { it.id }.toSet().size)
        assertTrue(vm.state.value.canExport)
    }

    @Test
    fun `blank uris are ignored`() = runTest(dispatcher) {
        val vm = DocScanViewModel(FakeExporter(ok()))
        vm.onPagesCaptured(listOf("", "  ", "content://a"))
        assertEquals(listOf("content://a"), vm.state.value.pages.map { it.uri })
    }

    @Test
    fun `move up and down reorders pages, no-op at the ends`() = runTest(dispatcher) {
        val vm = DocScanViewModel(FakeExporter(ok()))
        vm.onPagesCaptured(listOf("a", "b", "c"))
        val (id0, id1, id2) = vm.state.value.pages.map { it.id }

        vm.movePageDown(id0) // a,b,c -> b,a,c
        assertEquals(listOf("b", "a", "c"), vm.state.value.pages.map { it.uri })

        vm.movePageUp(id2) // b,a,c -> b,c,a
        assertEquals(listOf("b", "c", "a"), vm.state.value.pages.map { it.uri })

        vm.movePageUp(vm.state.value.pages.first().id) // already top -> no-op
        assertEquals(listOf("b", "c", "a"), vm.state.value.pages.map { it.uri })

        vm.movePageDown(vm.state.value.pages.last().id) // already bottom -> no-op
        assertEquals(listOf("b", "c", "a"), vm.state.value.pages.map { it.uri })
    }

    @Test
    fun `removePage drops the matching page`() = runTest(dispatcher) {
        val vm = DocScanViewModel(FakeExporter(ok()))
        vm.onPagesCaptured(listOf("a", "b"))
        val id = vm.state.value.pages.first().id
        vm.removePage(id)
        assertEquals(listOf("b"), vm.state.value.pages.map { it.uri })
    }

    @Test
    fun `export success sets result and a one-shot share request`() = runTest(dispatcher) {
        val exporter = FakeExporter(ok())
        val vm = DocScanViewModel(exporter)
        vm.onPagesCaptured(listOf("a", "b"))
        vm.onTitleChanged("My packet")

        vm.export()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, exporter.calls)
        assertEquals("My packet", exporter.lastRequest?.title)
        assertEquals(listOf("a", "b"), exporter.lastRequest?.pages?.map { it.uri })
        assertFalse(vm.state.value.isExporting)
        assertEquals("Doc.pdf", vm.state.value.exportResult?.fileName)
        // share is one-shot: present until the screen handles it.
        assertEquals("Doc.pdf", vm.state.value.shareRequest?.fileName)
        vm.onShareHandled()
        assertNull(vm.state.value.shareRequest)
        // exportResult persists so "Share again" still works.
        assertEquals("Doc.pdf", vm.state.value.exportResult?.fileName)
    }

    @Test
    fun `blank title falls back to a default`() = runTest(dispatcher) {
        val exporter = FakeExporter(ok())
        val vm = DocScanViewModel(exporter)
        vm.onPagesCaptured(listOf("a"))
        vm.export()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Scanned document", exporter.lastRequest?.title)
    }

    @Test
    fun `export failure surfaces an error and no share request`() = runTest(dispatcher) {
        val vm = DocScanViewModel(FakeExporter(DocPdfResult.Failure("boom")))
        vm.onPagesCaptured(listOf("a"))

        vm.export()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("boom", vm.state.value.error)
        assertNull(vm.state.value.shareRequest)
        assertNull(vm.state.value.exportResult)
    }

    @Test
    fun `export is a no-op with no pages`() = runTest(dispatcher) {
        val exporter = FakeExporter(ok())
        val vm = DocScanViewModel(exporter)
        vm.export()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, exporter.calls)
    }

    @Test
    fun `capturing after an export invalidates the stale result`() = runTest(dispatcher) {
        val vm = DocScanViewModel(FakeExporter(ok()))
        vm.onPagesCaptured(listOf("a"))
        vm.export()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("Doc.pdf", vm.state.value.exportResult?.fileName)

        vm.onPagesCaptured(listOf("b"))
        assertNull(vm.state.value.exportResult)
        assertNull(vm.state.value.shareRequest)
    }
}
