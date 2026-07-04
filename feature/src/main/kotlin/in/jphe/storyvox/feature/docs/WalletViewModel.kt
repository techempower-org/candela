package `in`.jphe.storyvox.feature.docs

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.docs.DocPageRef
import `in`.jphe.storyvox.data.docs.DocPdfExporter
import `in`.jphe.storyvox.data.docs.DocPdfRequest
import `in`.jphe.storyvox.data.docs.DocPdfResult
import `in`.jphe.storyvox.data.wallet.WalletDoc
import `in`.jphe.storyvox.data.wallet.WalletDocType
import `in`.jphe.storyvox.data.wallet.WalletProgramCatalog
import `in`.jphe.storyvox.data.wallet.WalletStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Issue #1514 — drives the encrypted "My Documents" wallet.
 *
 * The wallet stays LOCKED until the screen completes a device-credential
 * prompt (biometric / PIN / pattern) and calls [onUnlocked] — only then
 * does this VM read (decrypt) the
 * store, so nothing is decrypted behind a failed/absent auth. Documents
 * are added from the scanner/gallery, shown with a staleness hint, can
 * be re-exported to a shareable PDF (reusing the #1513 [DocPdfExporter]),
 * and answer "what does this prove?" from the verified program catalog.
 *
 * Plain-JVM logic behind [WalletStore] + [DocPdfExporter] seams —
 * unit-testable with fakes (no EncryptedFile, no biometric, no Android).
 */
@HiltViewModel
class WalletViewModel @Inject constructor(
    private val store: WalletStore,
    private val pdfExporter: DocPdfExporter,
) : ViewModel() {

    private val _state = MutableStateFlow(WalletUiState())
    val state: StateFlow<WalletUiState> = _state.asStateFlow()

    /** Biometric auth succeeded — unlock and load the (decrypted) list. */
    fun onUnlocked() {
        if (_state.value.unlocked) return
        _state.update { it.copy(unlocked = true) }
        load()
    }

    /** Biometric auth failed / was cancelled — surface a message; stay locked. */
    fun onAuthFailed(message: String?) {
        _state.update { it.copy(error = message) }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            val docs = store.list()
            _state.update { it.copy(isLoading = false, docs = docs) }
        }
    }

    /** Encrypt + store a freshly captured document. */
    fun addDocument(type: WalletDocType, title: String, note: String, pageUris: List<String>) {
        if (pageUris.isEmpty()) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            store.save(type, title, note, pageUris)
            val docs = store.list()
            _state.update { it.copy(isSaving = false, docs = docs) }
        }
    }

    fun delete(docId: String) {
        viewModelScope.launch {
            store.delete(docId)
            _state.update { it.copy(docs = store.list()) }
        }
    }

    /** Re-export a stored document as a shareable PDF: decrypt its pages
     *  to cache, compose via [DocPdfExporter], then wipe the decrypted
     *  temp files. Raises a one-shot [WalletUiState.shareRequest]. */
    fun reExport(docId: String, title: String) {
        if (_state.value.isExporting) return
        _state.update { it.copy(isExporting = true, error = null) }
        viewModelScope.launch {
            val uris = store.materializePagesToCache(docId)
            if (uris.isEmpty()) {
                _state.update { it.copy(isExporting = false, error = "Couldn't open that document.") }
                return@launch
            }
            val result = pdfExporter.exportToPdf(
                DocPdfRequest(title = title.ifBlank { "Document" }, pages = uris.map { DocPageRef(it) }),
            )
            store.clearMaterialized()
            when (result) {
                is DocPdfResult.Success -> {
                    val ready = DocExportReady(result.filePath, result.fileName, result.pageCount, result.byteSize)
                    _state.update { it.copy(isExporting = false, exportResult = ready, shareRequest = ready) }
                }

                is DocPdfResult.Failure -> _state.update {
                    it.copy(isExporting = false, error = result.message)
                }
            }
        }
    }

    /** Programs that accept [type] as proof (verified catalog; may be
     *  empty → the UI says "no verified list yet"). */
    fun programsFor(type: WalletDocType): List<WalletProgramCatalog.AcceptingProgram> =
        WalletProgramCatalog.programsAccepting(type)

    fun onShareHandled() = _state.update { it.copy(shareRequest = null) }

    fun clearError() = _state.update { it.copy(error = null) }
}

/** UI state for the wallet screen. */
@Immutable
data class WalletUiState(
    /** False until the device-credential prompt succeeds — nothing is decrypted before. */
    val unlocked: Boolean = false,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isExporting: Boolean = false,
    val docs: List<WalletDoc> = emptyList(),
    val error: String? = null,
    val exportResult: DocExportReady? = null,
    val shareRequest: DocExportReady? = null,
)
