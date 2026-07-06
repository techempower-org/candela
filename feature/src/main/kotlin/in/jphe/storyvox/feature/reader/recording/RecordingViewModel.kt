package `in`.jphe.storyvox.feature.reader.recording

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.playback.RecordingController
import `in`.jphe.storyvox.playback.RecordingRequest
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
    /** Issue #1367 (Wear PR1) — shared recording control state so the Wear
     *  remote can start/stop and mirror the REC timer. This VM is the phone-side
     *  owner: it acts on inbound [RecordingController.requests] and writes back
     *  `armed` / `recording` / `elapsedMs`. */
    private val recordingController: RecordingController,
    /** Issue #1633 — persist the recording-overlay knobs across sessions: seed
     *  the flows below from the stored defaults on init and write back on change
     *  (dual-write, mirroring how per-fiction speed/pitch persist). */
    private val settings: SettingsRepositoryUi,
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

    /** Script font size in sp (#1367 design ref — A−/A+). Sized for camera
     *  distance: a phone on a tripod needs bigger text than one held close. */
    private val _fontSize = MutableStateFlow(DEFAULT_FONT_SP)
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    /** Mirror the script horizontally for beam-splitter glass teleprompter
     *  rigs (the text reads correctly through the half-silvered mirror). */
    private val _mirror = MutableStateFlow(false)
    val mirror: StateFlow<Boolean> = _mirror.asStateFlow()

    private val _commands = Channel<RecordingCommand>(Channel.BUFFERED)

    /** One-shot camera instructions for the composable to execute against its
     *  lifecycle-bound [CameraRecorder]. */
    val commands = _commands.receiveAsFlow()

    private var countdownJob: Job? = null
    private var elapsedJob: Job? = null

    /** Issue #1633 — countdown length in seconds, seeded from the persisted pref
     *  on init (replaces the hardcoded [COUNTDOWN_SECONDS]). 0 = skip the
     *  countdown and begin recording immediately. */
    private var countdownSec: Int = COUNTDOWN_SECONDS

    init {
        // This VM's lifetime ≈ the RecordingScreen being on-screen with the
        // camera bound, so `armed` tells the Wear remote when its record button
        // is live ("open Recording on your phone first" otherwise).
        recordingController.setArmed(true)

        // #1633 — seed the overlay knobs from their persisted defaults so they
        // survive across sessions; each setter below writes the change back.
        viewModelScope.launch {
            val s = settings.settings.first()
            countdownSec = s.teleprompterCountdownSec
            _opacity.value = s.teleprompterOverlayOpacity
            _fontSize.value = s.teleprompterFontSizeSp
            _mirror.value = s.teleprompterMirror
            _frontCamera.value = s.teleprompterFrontCamera
        }

        // Inbound remote intents (watch): Start only begins a fresh take from
        // Preview; Stop defers to stopRecording()'s own Recording-only guard.
        viewModelScope.launch {
            recordingController.requests.collect { request ->
                when (request) {
                    RecordingRequest.Start ->
                        if (_uiState.value is RecordingState.Preview) startCountdown()
                    RecordingRequest.Stop -> stopRecording()
                }
            }
        }

        // Write recording state back to the controller for the watch to mirror.
        // elapsedMs is pushed only when the whole-second changes (the phone UI
        // ticks at 100ms for smoothness, but a 10Hz Wear DataItem sync would be
        // wasteful — the wrist timer is mm:ss).
        viewModelScope.launch {
            var lastSecond = -1L
            uiState.collect { state ->
                val elapsed = (state as? RecordingState.Recording)?.elapsedMs ?: 0L
                recordingController.setRecording(state is RecordingState.Recording)
                val second = elapsed / 1_000
                if (second != lastSecond) {
                    lastSecond = second
                    recordingController.setElapsedMs(second * 1_000)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Leaving RecordingScreen → the camera is gone; tell the watch it can no
        // longer drive a recording.
        recordingController.setArmed(false)
        recordingController.setRecording(false)
        recordingController.setElapsedMs(0L)
    }

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
            for (s in countdownSec downTo 1) {
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
            else -> {
                _frontCamera.value = !_frontCamera.value
                persist { setTeleprompterFrontCamera(_frontCamera.value) }
            }
        }
    }

    fun setOpacity(value: Float) {
        _opacity.value = value.coerceIn(MIN_OPACITY, MAX_OPACITY)
        persist { setTeleprompterOverlayOpacity(_opacity.value) }
    }

    /** Step the script font size (A− / A+) within the supported band. */
    fun adjustFontSize(deltaSp: Int) {
        _fontSize.value = (_fontSize.value + deltaSp).coerceIn(MIN_FONT_SP, MAX_FONT_SP)
        persist { setTeleprompterFontSizeSp(_fontSize.value) }
    }

    fun toggleMirror() {
        _mirror.value = !_mirror.value
        persist { setTeleprompterMirror(_mirror.value) }
    }

    /** #1633 — write an overlay-knob change back to the persisted defaults so it
     *  seeds the next recording session (dual-write, like speed/pitch). */
    private fun persist(write: suspend SettingsRepositoryUi.() -> Unit) {
        viewModelScope.launch { settings.write() }
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
        // Font band (sp). The web ref uses 24–120px on a broadcast monitor;
        // capped tighter here for a phone screen held / tripod-mounted.
        const val DEFAULT_FONT_SP = 26
        const val MIN_FONT_SP = 16
        const val MAX_FONT_SP = 64
        const val FONT_STEP_SP = 4
        private const val ELAPSED_TICK_MS = 100L
    }
}
