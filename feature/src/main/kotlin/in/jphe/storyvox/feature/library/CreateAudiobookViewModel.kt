package `in`.jphe.storyvox.feature.library

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.playback.audiobook.AudiobookExportScheduler
import `in`.jphe.storyvox.playback.audiobook.AudiobookExportStatus
import `in`.jphe.storyvox.playback.audiobook.CreateAudiobookFromTextUseCase
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the "Make your own audiobook" flow (issue #1003): the user pastes /
 * types text, names the book, picks a voice (a sensible default is
 * pre-selected so they can one-tap straight through), and we
 *   1. turn the text into a local fiction with chapters
 *      ([CreateAudiobookFromTextUseCase]), then
 *   2. enqueue the background render → encode → `.m4b`
 *      ([AudiobookExportScheduler]),
 * surfacing progress and finally the finished file for share / Save-As.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CreateAudiobookViewModel @Inject constructor(
    private val createFromText: CreateAudiobookFromTextUseCase,
    private val exportScheduler: AudiobookExportScheduler,
    private val voiceManager: VoiceManager,
) : ViewModel() {

    /** Installed voices the user can narrate with. Azure / System TTS are
     *  filtered out — the export path is offline-only (see
     *  AudiobookSynthesizer). */
    val voices: StateFlow<List<UiVoiceInfo>> = voiceManager.installedVoices
        .map { list ->
            list.filter { v ->
                v.engineType !is EngineType.Azure && v.engineType !is EngineType.SystemTts
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(CreateAudiobookUiState())
    val uiState: StateFlow<CreateAudiobookUiState> = _uiState.asStateFlow()

    /** The unique work name of the in-flight export, set on [start]. The
     *  status flow is keyed off it. */
    private val workName = MutableStateFlow<String?>(null)

    /** Live export status for the active job (Idle until [start]). */
    val exportStatus: StateFlow<AudiobookExportStatus> = workName
        .flatMapLatest { name ->
            if (name == null) flowOf(AudiobookExportStatus.Idle)
            else exportScheduler.statusFor(name)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudiobookExportStatus.Idle)

    /**
     * Kick off creation + export. [voiceId] null = the active voice. Creates
     * the local fiction first, then enqueues the render. The screen observes
     * [exportStatus] for progress + the finished file.
     */
    fun start(text: String, title: String, author: String, voiceId: String?) {
        if (_uiState.value.isStarting) return
        _uiState.value = _uiState.value.copy(isStarting = true, error = null)
        viewModelScope.launch {
            try {
                // Need at least one usable voice — fail fast with a clear msg
                // rather than a cryptic worker failure.
                val chosen = voiceId ?: voiceManager.activeVoice.first()?.id
                if (chosen == null && voices.first().isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isStarting = false,
                        error = "Install a voice first (Voices tab), then create your audiobook.",
                    )
                    return@launch
                }
                val fictionId = createFromText.create(text = text, title = title, author = author)
                val name = exportScheduler.enqueue(
                    fictionId = fictionId,
                    title = title.ifBlank { "My Audiobook" },
                    voiceId = voiceId,
                )
                workName.value = name
                _uiState.value = _uiState.value.copy(isStarting = false)
            } catch (t: Throwable) {
                _uiState.value = _uiState.value.copy(
                    isStarting = false,
                    error = t.message ?: "Couldn't start the audiobook",
                )
            }
        }
    }

    /** Reset for a fresh creation (after the user dismisses the result). */
    fun reset() {
        workName.value = null
        _uiState.value = CreateAudiobookUiState()
    }
}

@Immutable
data class CreateAudiobookUiState(
    /** True between the create tap and the work being enqueued. */
    val isStarting: Boolean = false,
    val error: String? = null,
)
