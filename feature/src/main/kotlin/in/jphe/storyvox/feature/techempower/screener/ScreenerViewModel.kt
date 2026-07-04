package `in`.jphe.storyvox.feature.techempower.screener

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
 * Issue #1517 — offline benefits screener ViewModel.
 *
 * ON-DEVICE ONLY (invariant 1): the only IO this ViewModel performs is reading
 * a bundled asset. There is NO network dependency, NO OkHttp, NO analytics —
 * answers live only in this in-memory state and never leave the device. That is
 * why the screener works in airplane mode and why the privacy promise is
 * *provable* here.
 *
 * Read-aloud (invariant 4) goes through the app-level [PlaybackControllerUi]
 * seam (`speakText`/`stopSpeaking`) — the same one the reader's recap-aloud and
 * chat use. We do NOT touch `core-playback`.
 */
@HiltViewModel
class ScreenerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    private val _state = MutableStateFlow(ScreenerUiState())
    val state: StateFlow<ScreenerUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val result = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.assets.open(CORPUS_ASSET).use { it.readBytes().decodeToString() }
                }
                ScreenerCorpusParser.parse(raw)
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

    /** Record a yes/no answer and recompute results if they're already shown. */
    fun answerBool(questionId: String, value: Boolean) = putAnswer(questionId, Answer.Bool(value))

    /** Record a single-select answer and recompute results if already shown. */
    fun answerChoice(questionId: String, optionId: String) =
        putAnswer(questionId, Answer.Choice(optionId))

    private fun putAnswer(questionId: String, answer: Answer) {
        _state.update { current ->
            val answers = current.answers + (questionId to answer)
            current.copy(
                answers = answers,
                results = if (current.showResults) recompute(current.corpus, answers) else current.results,
            )
        }
    }

    /** Compute (or refresh) the bucketed results and reveal them. */
    fun showResults() {
        _state.update { current ->
            current.copy(showResults = true, results = recompute(current.corpus, current.answers))
        }
    }

    /** Clear all answers and collapse the results — "start over". */
    fun reset() {
        stopReadAloud()
        _state.update { it.copy(answers = emptyMap(), results = emptyList(), showResults = false) }
    }

    /** Speak [text] via the active voice (app-level seam; not core-playback). */
    fun readAloud(text: String) {
        viewModelScope.launch { playback.speakText(text) }
    }

    /** Cancel any in-flight read-aloud utterance. Idempotent. */
    fun stopReadAloud() {
        playback.stopSpeaking()
    }

    private fun recompute(corpus: ScreenerCorpus?, answers: Map<String, Answer>): List<ScreenerResult> =
        corpus?.let { ScreenerEligibility.results(it, answers) } ?: emptyList()

    override fun onCleared() {
        stopReadAloud()
        super.onCleared()
    }

    companion object {
        const val CORPUS_ASSET = "techempower/screener_corpus.json"
    }
}

/** Immutable UI state for the screener surface. */
data class ScreenerUiState(
    val corpus: ScreenerCorpus? = null,
    val loadError: Boolean = false,
    val answers: Map<String, Answer> = emptyMap(),
    val results: List<ScreenerResult> = emptyList(),
    val showResults: Boolean = false,
) {
    val isLoading: Boolean get() = corpus == null && !loadError
}
