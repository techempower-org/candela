package `in`.jphe.storyvox.feature.docs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.docs.DocPdfResult
import `in`.jphe.storyvox.data.docs.FormOverlay
import `in`.jphe.storyvox.data.docs.FormPdfExporter
import `in`.jphe.storyvox.data.docs.FormPdfRequest
import `in`.jphe.storyvox.data.docs.NormPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Issue #1512 — drives the photo → fillable PDF flow: scan/pick a paper
 * form, place fields on it (tap-anywhere text, checkmarks, a drawn
 * signature), and export a **flattened** PDF that looks like the
 * completed form.
 *
 * Per the #1512 research verdict this uses flatten-via-`PdfDocument`
 * (the on-device [FormPdfExporter] seam), NOT AcroForm — the input is a
 * raster image with no form-field widgets to fill. Everything is
 * on-device (image, typed values, signature, PDF composition); no
 * network → inherently airplane-mode safe.
 *
 * All Android specifics live in the screen / behind [FormPdfExporter],
 * so this VM is plain JVM and unit-testable with a fake exporter.
 * Field positions are **normalized** (0..1 fractions of the page image)
 * so they survive rotation / different render sizes.
 */
@HiltViewModel
class FormFillViewModel @Inject constructor(
    private val exporter: FormPdfExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(FormFillUiState())
    val state: StateFlow<FormFillUiState> = _state.asStateFlow()

    /** Set (or replace) the form page image; clears any prior fields. */
    fun onPageCaptured(uri: String) {
        if (uri.isBlank()) return
        _state.update {
            FormFillUiState(pageImageUri = uri, title = it.title)
        }
    }

    /** Choose what a tap on the page places. */
    fun setTool(tool: FillTool) {
        _state.update { it.copy(activeTool = tool) }
    }

    /**
     * Handle a tap on the page at normalized ([x], [y]). Places a field
     * per the [FormFillUiState.activeTool] and selects it so the UI can
     * open its editor (text keyboard / signature pad).
     */
    fun onTapPage(x: Float, y: Float) {
        if (_state.value.pageImageUri == null) return
        val nx = x.coerceIn(0f, 1f)
        val ny = y.coerceIn(0f, 1f)
        _state.update { prev ->
            val id = prev.nextId
            val field = when (prev.activeTool) {
                FillTool.Text -> FormField.Text(id, nx, ny, text = "")
                FillTool.Check -> FormField.Check(id, nx, ny)
                FillTool.Signature -> FormField.Signature(
                    id = id,
                    x = (nx - DEFAULT_SIG_W / 2).coerceIn(0f, 1f - DEFAULT_SIG_W),
                    y = (ny - DEFAULT_SIG_H / 2).coerceIn(0f, 1f - DEFAULT_SIG_H),
                    widthFraction = DEFAULT_SIG_W,
                    heightFraction = DEFAULT_SIG_H,
                    strokes = emptyList(),
                )
            }
            prev.copy(
                fields = prev.fields + field,
                nextId = id + 1,
                selectedFieldId = id,
                // Placing a signature raises a one-shot so the screen opens
                // the draw pad on the new field id (robust — no stale read).
                pendingSignatureId = if (prev.activeTool == FillTool.Signature) id else null,
                exportResult = null,
                shareRequest = null,
                error = null,
            )
        }
    }

    /** Consume the one-shot signature-pad signal after the screen opens it. */
    fun consumePendingSignature() {
        _state.update { it.copy(pendingSignatureId = null) }
    }

    /** Update a text field's content. */
    fun updateText(id: Int, text: String) {
        _state.update { prev ->
            prev.copy(
                fields = prev.fields.map {
                    if (it is FormField.Text && it.id == id) it.copy(text = text) else it
                },
                exportResult = null,
                shareRequest = null,
            )
        }
    }

    /** Store the strokes drawn for a signature field (normalized within
     *  the field's box). */
    fun setSignatureStrokes(id: Int, strokes: List<List<NormPoint>>) {
        _state.update { prev ->
            prev.copy(
                fields = prev.fields.map {
                    if (it is FormField.Signature && it.id == id) it.copy(strokes = strokes) else it
                },
                exportResult = null,
                shareRequest = null,
            )
        }
    }

    /** Remove a field. */
    fun removeField(id: Int) {
        _state.update { prev ->
            prev.copy(
                fields = prev.fields.filterNot { it.id == id },
                selectedFieldId = prev.selectedFieldId?.takeIf { it != id },
                exportResult = null,
                shareRequest = null,
            )
        }
    }

    /** Select a field for editing (or clear the selection with null). */
    fun selectField(id: Int?) {
        _state.update { it.copy(selectedFieldId = id) }
    }

    fun onTitleChanged(title: String) {
        _state.update { it.copy(title = title) }
    }

    /** Compose the filled, flattened PDF and raise a one-shot share
     *  signal on success. */
    fun export() {
        val snapshot = _state.value
        val page = snapshot.pageImageUri ?: return
        if (snapshot.isExporting) return
        _state.update { it.copy(isExporting = true, error = null) }
        viewModelScope.launch {
            val request = FormPdfRequest(
                title = snapshot.title.ifBlank { DEFAULT_TITLE }.trim(),
                pageImageUri = page,
                overlays = snapshot.fields.mapNotNull { it.toOverlay() },
            )
            when (val result = exporter.exportFilledForm(request)) {
                is DocPdfResult.Success -> {
                    val ready = DocExportReady(
                        filePath = result.filePath,
                        fileName = result.fileName,
                        pageCount = result.pageCount,
                        byteSize = result.byteSize,
                    )
                    _state.update {
                        it.copy(isExporting = false, exportResult = ready, shareRequest = ready)
                    }
                }

                is DocPdfResult.Failure -> _state.update {
                    it.copy(isExporting = false, error = result.message)
                }
            }
        }
    }

    fun shareAgain() {
        _state.update { it.copy(shareRequest = it.exportResult) }
    }

    fun onShareHandled() {
        _state.update { it.copy(shareRequest = null) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private companion object {
        const val DEFAULT_TITLE = "Filled form"
        const val DEFAULT_SIG_W = 0.35f
        const val DEFAULT_SIG_H = 0.10f
    }
}

/** What a tap on the page places. */
enum class FillTool { Text, Check, Signature }

/** UI state for the form-fill screen. */
@Immutable
data class FormFillUiState(
    val pageImageUri: String? = null,
    val fields: List<FormField> = emptyList(),
    val activeTool: FillTool = FillTool.Text,
    val selectedFieldId: Int? = null,
    /** One-shot: set to a newly-placed signature field's id so the screen
     *  opens the draw pad, then cleared via [FormFillViewModel.consumePendingSignature]. */
    val pendingSignatureId: Int? = null,
    val title: String = "",
    val isExporting: Boolean = false,
    val error: String? = null,
    val exportResult: DocExportReady? = null,
    val shareRequest: DocExportReady? = null,
    val nextId: Int = 0,
) {
    val hasPage: Boolean get() = pageImageUri != null
    val canExport: Boolean get() = pageImageUri != null && !isExporting
}

/** One field placed on the form. Positions are normalized (0..1). */
@Immutable
sealed interface FormField {
    val id: Int

    @Immutable
    data class Text(
        override val id: Int,
        val x: Float,
        val y: Float,
        val text: String,
    ) : FormField

    @Immutable
    data class Check(
        override val id: Int,
        val x: Float,
        val y: Float,
    ) : FormField

    @Immutable
    data class Signature(
        override val id: Int,
        val x: Float,
        val y: Float,
        val widthFraction: Float,
        val heightFraction: Float,
        val strokes: List<List<NormPoint>>,
    ) : FormField
}

/** Map a UI field to the exporter overlay; returns null for a field that
 *  contributes nothing (empty text, un-drawn signature). */
internal fun FormField.toOverlay(): FormOverlay? = when (this) {
    is FormField.Text -> if (text.isBlank()) null else FormOverlay.TextBox(x = x, y = y, text = text)
    is FormField.Check -> FormOverlay.Checkmark(x = x, y = y)
    is FormField.Signature ->
        if (strokes.all { it.size < 2 }) {
            null
        } else {
            FormOverlay.Signature(
                x = x,
                y = y,
                widthFraction = widthFraction,
                heightFraction = heightFraction,
                strokes = strokes,
            )
        }
}
