package `in`.jphe.storyvox.feature.techempower.learnpaths

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.FictionRepositoryUi
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Issue #1464 — learning-paths ViewModel.
 *
 * Loads the bundled path definitions, then folds in the Guides fiction's
 * chapter-played state (`FictionRepositoryUi.chaptersFor(...).isFinished`, the
 * same `userMarkedRead` column playback writes on chapter end) to compute
 * per-path progress via the pure [LearningPathProgress].
 *
 * Subscribing to [FictionRepositoryUi.fictionById] kicks the Guides detail
 * refresh so chapter rows exist even if the learner never opened Guides from
 * Browse; failures (offline) are tolerated — progress just reads as zero.
 *
 * Starting a step goes through [PlaybackControllerUi.startListening] BEFORE the
 * screen navigates — the reader is a passive view and does not auto-load.
 */
@HiltViewModel
class LearningPathsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val fictions: FictionRepositoryUi,
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    private val _state = MutableStateFlow(LearningPathsUiState())
    val state: StateFlow<LearningPathsUiState> = _state.asStateFlow()

    private val _events = Channel<LearningPathsEvent>(Channel.BUFFERED)
    val events: Flow<LearningPathsEvent> = _events.receiveAsFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val result = runCatching {
                val raw = withContext(Dispatchers.IO) {
                    context.assets.open(CORPUS_ASSET).use { it.readBytes().decodeToString() }
                }
                LearningPathsParser.parse(raw)
            }
            val corpus = result.getOrNull()
            _state.update { st ->
                if (corpus != null) st.copy(corpus = corpus, loadError = false)
                else st.copy(corpus = null, loadError = true)
            }
            if (corpus != null) observeProgress(corpus.fictionId)
        }
    }

    private fun observeProgress(fictionId: String) {
        // Kick the detail refresh (hydrates chapter rows); value is unused.
        viewModelScope.launch {
            fictions.fictionById(fictionId).catch { }.collect { }
        }
        viewModelScope.launch {
            fictions.chaptersFor(fictionId)
                .catch { emit(emptyList()) }
                .collect { chapters ->
                    val finished = chapters.asSequence().filter { it.isFinished }.map { it.id }.toSet()
                    _state.update { it.copy(finishedChapterIds = finished) }
                }
        }
    }

    fun select(pathId: String) {
        _state.update { it.copy(selectedPathId = pathId) }
    }

    fun backToList() {
        _state.update { it.copy(selectedPathId = null) }
    }

    /** Start narrating [step] and ask the screen to open the reader. */
    fun startStep(step: PathStep) {
        val corpus = _state.value.corpus ?: return
        val chapterId = corpus.chapterIdFor(step)
        playback.startListening(corpus.fictionId, chapterId)
        viewModelScope.launch { _events.send(LearningPathsEvent.OpenReader(corpus.fictionId, chapterId)) }
    }

    companion object {
        const val CORPUS_ASSET = "techempower/learning_paths.json"
    }
}

sealed interface LearningPathsEvent {
    data class OpenReader(val fictionId: String, val chapterId: String) : LearningPathsEvent
}

data class LearningPathsUiState(
    val corpus: LearningPathsCorpus? = null,
    val loadError: Boolean = false,
    val finishedChapterIds: Set<String> = emptySet(),
    val selectedPathId: String? = null,
) {
    val isLoading: Boolean get() = corpus == null && !loadError

    val progress: List<PathProgress>
        get() = corpus?.let { LearningPathProgress.progressForAll(it, finishedChapterIds) }.orEmpty()

    val recommended: PathProgress? get() = LearningPathProgress.recommended(progress)

    val selectedPath: LearningPath? get() = corpus?.paths?.firstOrNull { it.id == selectedPathId }

    fun pathById(id: String): LearningPath? = corpus?.paths?.firstOrNull { it.id == id }

    fun progressOf(id: String): PathProgress? = progress.firstOrNull { it.pathId == id }
}
