package `in`.jphe.storyvox.feature.techempower.calls

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Issue #1518 — "make the call" ViewModel.
 *
 * Holds the call-card corpus, the selected card, per-card captured answers, and
 * the post-call capture flow. Dialing is `Intent.ACTION_DIAL` (no CALL_PHONE
 * permission, no call log). When the user returns to the app after a dial we
 * offer the capture sheet — detected via a foreground-return signal from the
 * screen ([onReturnedToForeground]); NO call-log access.
 *
 * Answer capture is on-device, in this session's state only. Durable, encrypted
 * persistence is deferred to the wallet storage seam:
 *   // TODO(#1514): persist captured call answers via the encrypted wallet seam
 *   // once it lands, instead of session-only memory. Do not add a parallel
 *   // sensitive store (epic #1520 cross-lane guidance).
 *
 * Read-aloud goes through the app-level [PlaybackControllerUi] seam.
 * `core-playback` is untouched.
 */
@HiltViewModel
class CallCardsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    private val _state = MutableStateFlow(CallCardsUiState())
    val state: StateFlow<CallCardsUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val result = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.assets.open(CORPUS_ASSET).use { it.readBytes().decodeToString() }
                }
                CallCardsParser.parse(raw)
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

    fun select(cardId: String) {
        _state.update { it.copy(selectedCardId = cardId, dialInitiated = false, showCapture = false) }
    }

    fun backToList() {
        stopReadAloud()
        _state.update { it.copy(selectedCardId = null, dialInitiated = false, showCapture = false) }
    }

    /** The screen fires the dial intent; this arms the return-to-capture flow. */
    fun onDialInitiated() {
        _state.update { it.copy(dialInitiated = true) }
    }

    /**
     * Called on the screen's ON_RESUME. If a dial was just initiated, offer the
     * capture sheet exactly once.
     */
    fun onReturnedToForeground() {
        _state.update { current ->
            if (current.dialInitiated && current.selectedCardId != null && !current.showCapture) {
                current.copy(showCapture = true, dialInitiated = false)
            } else {
                current
            }
        }
    }

    /** Manually open the capture sheet (e.g. "I already made the call"). */
    fun openCapture() {
        _state.update { if (it.selectedCardId != null) it.copy(showCapture = true) else it }
    }

    fun dismissCapture() {
        _state.update { it.copy(showCapture = false) }
    }

    fun updateCapture(cardId: String, fieldId: String, value: String) {
        _state.update { current ->
            val forCard = current.captureByCard[cardId].orEmpty() + (fieldId to value)
            current.copy(captureByCard = current.captureByCard + (cardId to forCard))
        }
    }

    fun readAloud(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch { playback.speakText(text) }
    }

    fun stopReadAloud() {
        playback.stopSpeaking()
    }

    override fun onCleared() {
        stopReadAloud()
        super.onCleared()
    }

    companion object {
        const val CORPUS_ASSET = "techempower/call_cards.json"
    }
}

data class CallCardsUiState(
    val corpus: CallCardsCorpus? = null,
    val loadError: Boolean = false,
    val selectedCardId: String? = null,
    val captureByCard: Map<String, Map<String, String>> = emptyMap(),
    val dialInitiated: Boolean = false,
    val showCapture: Boolean = false,
) {
    val isLoading: Boolean get() = corpus == null && !loadError
    val selectedCard: CallCard? get() = corpus?.cards?.firstOrNull { it.id == selectedCardId }
}
