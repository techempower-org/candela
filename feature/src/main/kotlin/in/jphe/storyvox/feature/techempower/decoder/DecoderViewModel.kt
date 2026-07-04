package `in`.jphe.storyvox.feature.techempower.decoder

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.source.ocr.config.OcrConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Issue #1516 — benefits letter decoder ViewModel.
 *
 * Pipeline: notice text (typed, pasted, or reused from the most recent OCR
 * scan) → [NoticeFormDetector] → verified [NoticeExplainer] from the corpus.
 * Unknown form → honest [DecodeResult.Unknown] fallback (read-aloud of the
 * scanned text + call 211), NEVER a generated explanation (invariant 3).
 *
 * REUSE (epic #1520): OCR text comes from the existing scan pipeline via
 * [OcrConfig] (no camera code here); read-aloud goes through the app-level
 * [PlaybackControllerUi] seam. `core-playback` is untouched.
 *
 * ON-DEVICE ONLY: the only IO is reading a bundled asset + the local OCR store.
 * No network, no analytics — the letter's text never leaves the device.
 */
@HiltViewModel
class DecoderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackControllerUi,
    private val ocrConfig: OcrConfig,
) : ViewModel() {

    private val _state = MutableStateFlow(DecoderUiState())
    val state: StateFlow<DecoderUiState> = _state.asStateFlow()

    init {
        loadCorpus()
        observeScans()
    }

    private fun loadCorpus() {
        viewModelScope.launch {
            val result = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.assets.open(CORPUS_ASSET).use { it.readBytes().decodeToString() }
                }
                ExplainerCorpusParser.parse(raw)
            }
            // `st` names the outer state param explicitly — inside fold, the
            // onFailure lambda's implicit `it` is the Throwable, not the state,
            // so `it.copy(...)` would not resolve.
            _state.update { st ->
                result.fold(
                    onSuccess = { corpus -> st.copy(corpus = corpus, loadError = false) },
                    onFailure = { st.copy(corpus = null, loadError = true) },
                )
            }
        }
    }

    private fun observeScans() {
        viewModelScope.launch {
            ocrConfig.documents.collect { docs ->
                _state.update { it.copy(hasRecentScan = docs.isNotEmpty()) }
            }
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    /** Detect the form number in the current input and resolve an explainer. */
    fun decode() {
        _state.update { current ->
            val corpus = current.corpus ?: return@update current
            val text = current.inputText
            val explainer = NoticeFormDetector.firstKnown(text, corpus)
            val result = if (explainer != null) {
                DecodeResult.Known(explainer)
            } else {
                DecodeResult.Unknown(text)
            }
            current.copy(result = result)
        }
    }

    /** Pull the newest OCR scan's text into the input, then decode it. */
    fun useMostRecentScan() {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                ocrConfig.documents().firstOrNull()
                    ?.pages
                    ?.joinToString("\n") { it.text }
                    .orEmpty()
            }
            if (text.isNotBlank()) {
                _state.update { it.copy(inputText = text) }
                decode()
            }
        }
    }

    fun readAloud(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { playback.speakText(text) }
    }

    fun stopReadAloud() {
        playback.stopSpeaking()
    }

    fun reset() {
        stopReadAloud()
        _state.update { it.copy(inputText = "", result = null) }
    }

    override fun onCleared() {
        stopReadAloud()
        super.onCleared()
    }

    companion object {
        const val CORPUS_ASSET = "techempower/notice_explainers.json"
    }
}

/** Result of decoding notice text. */
sealed interface DecodeResult {
    /** A verified explainer matched the detected form number. */
    data class Known(val explainer: NoticeExplainer) : DecodeResult

    /** No verified explainer — honest fallback. [scannedText] feeds read-aloud. */
    data class Unknown(val scannedText: String) : DecodeResult
}

data class DecoderUiState(
    val corpus: ExplainerCorpus? = null,
    val loadError: Boolean = false,
    val inputText: String = "",
    val result: DecodeResult? = null,
    val hasRecentScan: Boolean = false,
) {
    val isLoading: Boolean get() = corpus == null && !loadError
}
