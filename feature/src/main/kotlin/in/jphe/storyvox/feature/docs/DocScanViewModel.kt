package `in`.jphe.storyvox.feature.docs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.docs.DocPageRef
import `in`.jphe.storyvox.data.docs.DocPdfExporter
import `in`.jphe.storyvox.data.docs.DocPdfRequest
import `in`.jphe.storyvox.data.docs.DocPdfResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Issue #1513 — drives the multi-page document scanner → shareable PDF
 * flow ("no scanner at home").
 *
 * The screen captures pages (ML Kit Document Scanner, or a gallery pick
 * fallback on de-Googled devices) and hands their image URIs to
 * [onPagesCaptured]. This VM accumulates them, lets the user reorder /
 * delete before export, and composes them into one compact PDF through
 * the on-device [DocPdfExporter] seam. The export result carries the
 * finished file path, which the screen shares via `ACTION_SEND`.
 *
 * All Android / ML Kit specifics live in the screen and behind the
 * [DocPdfExporter] interface, so this VM is plain JVM logic and
 * unit-testable with a fake exporter (no Robolectric) — the same posture
 * as [`in`.jphe.storyvox.feature.ocr.OcrCaptureViewModel].
 */
@HiltViewModel
class DocScanViewModel @Inject constructor(
    private val exporter: DocPdfExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(DocScanUiState())
    val state: StateFlow<DocScanUiState> = _state.asStateFlow()

    /** Append freshly captured / picked page images, in the order given. */
    fun onPagesCaptured(uris: List<String>) {
        val clean = uris.filter { it.isNotBlank() }
        if (clean.isEmpty()) return
        _state.update { prev ->
            var nextId = prev.nextId
            val added = clean.map { DocScanPage(id = nextId++, uri = it) }
            prev.copy(
                pages = prev.pages + added,
                nextId = nextId,
                error = null,
                // A new capture invalidates any prior export.
                exportResult = null,
                shareRequest = null,
            )
        }
    }

    /** The scanner client couldn't start (Play services missing / a
     *  de-Googled device). Surface the fallback hint; the gallery-pick
     *  path stays available. */
    fun onScannerUnavailable() {
        _state.update { it.copy(scannerUnavailable = true) }
    }

    /** Update the user-editable document title (names the exported file). */
    fun onTitleChanged(title: String) {
        _state.update { it.copy(title = title) }
    }

    /** Drop a page (a bad shot, or a duplicate). */
    fun removePage(id: Int) {
        _state.update { prev ->
            prev.copy(
                pages = prev.pages.filterNot { it.id == id },
                exportResult = null,
                shareRequest = null,
            )
        }
    }

    /** Move a page one slot earlier (list-based reorder — the
     *  TalkBack-friendly alternative to a drag handle). No-op at the top. */
    fun movePageUp(id: Int) = reorder(id, -1)

    /** Move a page one slot later. No-op at the bottom. */
    fun movePageDown(id: Int) = reorder(id, +1)

    private fun reorder(id: Int, delta: Int) {
        _state.update { prev ->
            val list = prev.pages
            val i = list.indexOfFirst { it.id == id }
            val j = i + delta
            if (i < 0 || j < 0 || j > list.lastIndex) return@update prev
            val mutable = list.toMutableList()
            mutable[i] = mutable[j].also { mutable[j] = mutable[i] }
            prev.copy(pages = mutable, exportResult = null, shareRequest = null)
        }
    }

    /** Compose the accumulated pages into a single PDF and, on success,
     *  raise a one-shot [DocScanUiState.shareRequest] the screen consumes
     *  to open the share sheet. */
    fun export() {
        val snapshot = _state.value
        if (snapshot.pages.isEmpty() || snapshot.isExporting) return
        _state.update { it.copy(isExporting = true, error = null) }
        viewModelScope.launch {
            val request = DocPdfRequest(
                title = snapshot.title.ifBlank { DEFAULT_TITLE }.trim(),
                pages = snapshot.pages.map { DocPageRef(it.uri) },
            )
            when (val result = exporter.exportToPdf(request)) {
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

    /** Re-open the share sheet for an already-exported PDF. */
    fun shareAgain() {
        _state.update { it.copy(shareRequest = it.exportResult) }
    }

    /** Consume the one-shot share signal after the chooser is launched. */
    fun onShareHandled() {
        _state.update { it.copy(shareRequest = null) }
    }

    /** Clear a surfaced error once acknowledged. */
    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private companion object {
        const val DEFAULT_TITLE = "Scanned document"
    }
}

/** UI state for the document-scanner screen. */
@Immutable
data class DocScanUiState(
    val title: String = "",
    val pages: List<DocScanPage> = emptyList(),
    val isExporting: Boolean = false,
    /** True once a scanner launch failed — the UI leans on gallery pick. */
    val scannerUnavailable: Boolean = false,
    /** User-facing recoverable error (export failed). */
    val error: String? = null,
    /** The most recent successful export (drives the "Share again" row). */
    val exportResult: DocExportReady? = null,
    /** One-shot: non-null exactly until the screen fires the share sheet,
     *  then cleared via [DocScanViewModel.onShareHandled]. */
    val shareRequest: DocExportReady? = null,
    /** Monotonic id source for [DocScanPage]s. */
    val nextId: Int = 0,
) {
    val canExport: Boolean get() = pages.isNotEmpty() && !isExporting
    val pageCount: Int get() = pages.size
}

/** One captured page held in the scan session. [uri] is a
 *  `content://`/`file://` image URI string. */
@Immutable
data class DocScanPage(
    val id: Int,
    val uri: String,
)

/** A finished PDF ready to share. */
@Immutable
data class DocExportReady(
    val filePath: String,
    val fileName: String,
    val pageCount: Int,
    val byteSize: Long,
)
