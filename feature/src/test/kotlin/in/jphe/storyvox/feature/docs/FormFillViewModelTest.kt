package `in`.jphe.storyvox.feature.docs

import `in`.jphe.storyvox.data.docs.DocPdfResult
import `in`.jphe.storyvox.data.docs.FormOverlay
import `in`.jphe.storyvox.data.docs.FormPdfExporter
import `in`.jphe.storyvox.data.docs.FormPdfRequest
import `in`.jphe.storyvox.data.docs.NormPoint
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
 * Issue #1512 — FormFillViewModel logic over a fake exporter (no
 * PdfDocument, no ContentResolver, no Robolectric). Also serves as the
 * **airplane-mode assertion** at the JVM level: the fake exporter makes
 * no network call and the whole fill→export flow completes purely from
 * in-memory state, mirroring the real on-device path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FormFillViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private class FakeExporter(var next: DocPdfResult) : FormPdfExporter {
        var calls = 0
        var lastRequest: FormPdfRequest? = null
        override suspend fun exportFilledForm(request: FormPdfRequest): DocPdfResult {
            calls++
            lastRequest = request
            return next
        }
    }

    private fun ok() = DocPdfResult.Success(
        filePath = "/cache/exports/Form.pdf",
        fileName = "Form.pdf",
        pageCount = 1,
        byteSize = 180_000L,
    )

    @Test
    fun `capturing a page enables the canvas and clears prior fields`() = runTest(dispatcher) {
        val vm = FormFillViewModel(FakeExporter(ok()))
        vm.onPageCaptured("content://form")
        vm.setTool(FillTool.Check)
        vm.onTapPage(0.5f, 0.5f)
        assertTrue(vm.state.value.hasPage)
        assertEquals(1, vm.state.value.fields.size)

        // Re-capturing resets fields but keeps the typed title.
        vm.onTitleChanged("LifeLine")
        vm.onPageCaptured("content://form2")
        assertEquals("content://form2", vm.state.value.pageImageUri)
        assertTrue(vm.state.value.fields.isEmpty())
        assertEquals("LifeLine", vm.state.value.title)
    }

    @Test
    fun `tap in text tool adds a selected empty text field, then fill it`() = runTest(dispatcher) {
        val vm = FormFillViewModel(FakeExporter(ok()))
        vm.onPageCaptured("content://form")
        vm.setTool(FillTool.Text)
        vm.onTapPage(0.2f, 0.3f)

        val field = vm.state.value.fields.single()
        assertTrue(field is FormField.Text)
        assertEquals(field.id, vm.state.value.selectedFieldId)

        vm.updateText(field.id, "Jane Q. Public")
        val text = vm.state.value.fields.single() as FormField.Text
        assertEquals("Jane Q. Public", text.text)
        assertEquals(0.2f, text.x, 0.0001f)
        assertEquals(0.3f, text.y, 0.0001f)
    }

    @Test
    fun `tap coordinates are clamped to the page`() = runTest(dispatcher) {
        val vm = FormFillViewModel(FakeExporter(ok()))
        vm.onPageCaptured("content://form")
        vm.onTapPage(1.5f, -0.2f)
        val t = vm.state.value.fields.single() as FormField.Text
        assertEquals(1f, t.x, 0.0001f)
        assertEquals(0f, t.y, 0.0001f)
    }

    @Test
    fun `signature tap places a clamped box; strokes attach`() = runTest(dispatcher) {
        val vm = FormFillViewModel(FakeExporter(ok()))
        vm.onPageCaptured("content://form")
        vm.setTool(FillTool.Signature)
        vm.onTapPage(0.95f, 0.95f) // near the corner → box clamps inside

        val sig = vm.state.value.fields.single() as FormField.Signature
        assertTrue(sig.x + sig.widthFraction <= 1.0001f)
        assertTrue(sig.y + sig.heightFraction <= 1.0001f)

        val strokes = listOf(listOf(NormPoint(0f, 0.5f), NormPoint(1f, 0.5f)))
        vm.setSignatureStrokes(sig.id, strokes)
        assertEquals(strokes, (vm.state.value.fields.single() as FormField.Signature).strokes)
    }

    @Test
    fun `removeField drops it and clears its selection`() = runTest(dispatcher) {
        val vm = FormFillViewModel(FakeExporter(ok()))
        vm.onPageCaptured("content://form")
        vm.onTapPage(0.1f, 0.1f)
        val id = vm.state.value.fields.single().id
        vm.removeField(id)
        assertTrue(vm.state.value.fields.isEmpty())
        assertNull(vm.state.value.selectedFieldId)
    }

    @Test
    fun `export maps fields to overlays, skipping empty ones, and shares`() = runTest(dispatcher) {
        val exporter = FakeExporter(ok())
        val vm = FormFillViewModel(exporter)
        vm.onPageCaptured("content://form")

        // A filled text, a checkmark, an empty text (skipped), a strokeless
        // signature (skipped), and a real signature.
        vm.setTool(FillTool.Text); vm.onTapPage(0.1f, 0.1f)
        val nameId = vm.state.value.fields.last().id
        vm.updateText(nameId, "Jane")
        vm.setTool(FillTool.Check); vm.onTapPage(0.2f, 0.2f)
        vm.setTool(FillTool.Text); vm.onTapPage(0.3f, 0.3f) // left blank
        vm.setTool(FillTool.Signature); vm.onTapPage(0.4f, 0.8f) // no strokes
        vm.setTool(FillTool.Signature); vm.onTapPage(0.5f, 0.8f)
        val sigId = vm.state.value.fields.last().id
        vm.setSignatureStrokes(sigId, listOf(listOf(NormPoint(0f, 0f), NormPoint(1f, 1f))))
        vm.onTitleChanged("CA LifeLine")

        vm.export()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, exporter.calls)
        val req = exporter.lastRequest!!
        assertEquals("CA LifeLine", req.title)
        assertEquals("content://form", req.pageImageUri)
        // 5 placed fields → 3 overlays (blank text + strokeless sig dropped).
        assertEquals(3, req.overlays.size)
        assertEquals(1, req.overlays.filterIsInstance<FormOverlay.TextBox>().size)
        assertEquals(1, req.overlays.filterIsInstance<FormOverlay.Checkmark>().size)
        assertEquals(1, req.overlays.filterIsInstance<FormOverlay.Signature>().size)

        assertEquals("Form.pdf", vm.state.value.exportResult?.fileName)
        assertEquals("Form.pdf", vm.state.value.shareRequest?.fileName)
        vm.onShareHandled()
        assertNull(vm.state.value.shareRequest)
    }

    @Test
    fun `zero-detected-fields form still exports (blank page passes through)`() = runTest(dispatcher) {
        val exporter = FakeExporter(ok())
        val vm = FormFillViewModel(exporter)
        vm.onPageCaptured("content://blankform")
        assertTrue(vm.state.value.canExport)

        vm.export()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, exporter.calls)
        assertTrue(exporter.lastRequest!!.overlays.isEmpty())
        assertEquals("Form.pdf", vm.state.value.exportResult?.fileName)
    }

    @Test
    fun `export is a no-op without a page image`() = runTest(dispatcher) {
        val exporter = FakeExporter(ok())
        val vm = FormFillViewModel(exporter)
        vm.export()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(0, exporter.calls)
        assertFalse(vm.state.value.canExport)
    }

    @Test
    fun `export failure surfaces an error and no share`() = runTest(dispatcher) {
        val vm = FormFillViewModel(FakeExporter(DocPdfResult.Failure("nope")))
        vm.onPageCaptured("content://form")
        vm.export()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("nope", vm.state.value.error)
        assertNull(vm.state.value.shareRequest)
        assertNull(vm.state.value.exportResult)
    }
}
