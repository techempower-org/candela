package `in`.jphe.storyvox.feature.reader.recording

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.playback.TeleprompterController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Issue #1367 — Recording mode ViewModel.
 *
 * Drives the [RecordingState] machine for [RecordingScreen]. The script and
 * its pace are read straight off the two playback singletons the reader
 * already shares — no nav argument or bespoke holder:
 *  - [PlaybackControllerUi.chapterText] is the chapter currently loaded in the
 *    reader (the same text the teleprompter scrolls), used as the script.
 *  - [PlaybackControllerUi.state] supplies the title for the saved clip's name.
 *  - [TeleprompterController.wpm] is the live rehearsal pace (#1308) the
 *    overlay auto-scrolls at, so Recording mode inherits whatever the user
 *    last set in the reader's teleprompter transport.
 *
 * The ViewModel deliberately holds **no** CameraX types: it emits
 * [RecordingCommand]s for the lifecycle-owning composable to run and receives
 * results back via [onRecordingFinalized] / [onRecordingError]. That keeps the
 * countdown / elapsed / save state logic a plain, device-free unit.
 */
@HiltViewModel
class RecordingViewModel @Inject constructor(
    playback: PlaybackControllerUi,
    teleprompter: TeleprompterController,
) : ViewModel() {

    /** The chapter text to read — the same script the reader's teleprompter
     *  shows. Empty until a chapter is loaded (the Record entry point only
     *  exists once the teleprompter is on, so it's populated in practice). */
    val script: StateFlow<String> = playback.chapterText
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** Display title for the saved clip's filename — chapter title, falling
     *  back to the fiction title, then a generic label. */
    val title: StateFlow<String> = playback.state
        .map { it.chapterTitle.ifBlank { it.fictionTitle }.ifBlank { "Candela script" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Candela script")

    /** Live teleprompter pace (#1308), inherited from the reader. */
    val wpm: StateFlow<Int> = teleprompter.wpm

    private val _uiState = MutableStateFlow<RecordingState>(RecordingState.Preview)
    val uiState: StateFlow<RecordingState> = _uiState.asStateFlow()

    /** Script-overlay opacity (white-on-camera), 30–100%. Default 70% per
     *  #1367. Adjustable live so the user can trade legibility against how
     *  much of their face the text covers. */
    private val _opacity = MutableStateFlow(DEFAULT_OPACITY)
    val opacity: StateFlow<Float> = _opacity.asStateFlow()

    /** True = front (selfie) camera. Front by default — this is a
     *  read-to-camera workflow. */
    private val _frontCamera = MutableStateFlow(true)
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    private val _commands = Channel<RecordingCommand>(Channel.BUFFERED)

    /** One-shot camera instructions for the composable to execute against its
     *  lifecycle-bound [CameraRecorder]. */
    val commands = _commands.receiveAsFlow()

    private var countdownJob: Job? = null
    private var elapsedJob: Job? = null

    /**
     * The record/stop button. Context-sensitive:
     *  - [RecordingState.Preview] → start the 3-2-1 countdown.
     *  - [RecordingState.Countdown] → abort back to preview (tap to cancel).
     *  - [RecordingState.Recording] → stop and save.
     *  - [RecordingState.Done] / [RecordingState.Error] → reset to preview
     *    ("record again").
     */
    fun onRecordButton() {
        when (_uiState.value) {
            is RecordingState.Preview -> startCountdown()
            is RecordingState.Countdown -> abortCountdown()
            is RecordingState.Recording -> stopRecording()
            is RecordingState.Done, is RecordingState.Error -> reset()
            is RecordingState.Saving -> Unit // wait for finalize
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (s in COUNTDOWN_SECONDS downTo 1) {
                _uiState.value = RecordingState.Countdown(s)
                delay(1_000)
            }
            beginRecording()
        }
    }

    private fun abortCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        _uiState.value = RecordingState.Preview
    }

    private fun beginRecording() {
        _uiState.value = RecordingState.Recording(0L)
        _commands.trySend(RecordingCommand.Start(buildDisplayName()))
        elapsedJob?.cancel()
        elapsedJob = viewModelScope.launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive && _uiState.value is RecordingState.Recording) {
                _uiState.value = RecordingState.Recording(SystemClock.elapsedRealtime() - start)
                delay(ELAPSED_TICK_MS)
            }
        }
    }

    /** Stop the active recording — the composable finalizes the MP4 and reports
     *  back via [onRecordingFinalized] / [onRecordingError]. */
    fun stopRecording() {
        if (_uiState.value !is RecordingState.Recording) return
        elapsedJob?.cancel()
        elapsedJob = null
        _uiState.value = RecordingState.Saving
        _commands.trySend(RecordingCommand.Stop)
    }

    /** Camera reported the clip finalized to the gallery. */
    fun onRecordingFinalized(uri: Uri) {
        _uiState.value = RecordingState.Done(uri)
    }

    /** Camera reported a recording / finalization failure. */
    fun onRecordingError(message: String) {
        countdownJob?.cancel()
        elapsedJob?.cancel()
        _uiState.value = RecordingState.Error(message)
    }

    /** Flip front/back. Disallowed while a recording or save is in flight —
     *  rebinding the camera mid-capture would abort the clip. */
    fun flipCamera() {
        when (_uiState.value) {
            is RecordingState.Recording, is RecordingState.Saving -> return
            else -> _frontCamera.value = !_frontCamera.value
        }
    }

    fun setOpacity(value: Float) {
        _opacity.value = value.coerceIn(MIN_OPACITY, MAX_OPACITY)
    }

    /** Back to a clean live preview (after a save, an error, or "record
     *  again"). */
    fun reset() {
        countdownJob?.cancel()
        elapsedJob?.cancel()
        countdownJob = null
        elapsedJob = null
        _uiState.value = RecordingState.Preview
    }

    private fun buildDisplayName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTitle = title.value
            .replace(Regex("[^A-Za-z0-9 ]"), "")
            .trim()
            .take(40)
            .ifBlank { "script" }
            .replace(' ', '_')
        return "Candela_${safeTitle}_$stamp"
    }

    companion object {
        const val COUNTDOWN_SECONDS = 3
        const val DEFAULT_OPACITY = 0.7f
        const val MIN_OPACITY = 0.3f
        const val MAX_OPACITY = 1.0f
        private const val ELAPSED_TICK_MS = 100L
    }
}
