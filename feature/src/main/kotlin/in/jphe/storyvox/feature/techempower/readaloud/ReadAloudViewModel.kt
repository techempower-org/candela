package `in`.jphe.storyvox.feature.techempower.readaloud

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Issue #1580 — the single read-aloud seam behind every [ReadAloudControl] on
 * the TechEMPOWER benefits surfaces.
 *
 * Mirrors the #1571 pilot: speech goes through the app-level
 * [PlaybackControllerUi.speakText] / [PlaybackControllerUi.stopSpeaking]
 * one-shot utterance path (the same pipeline as the reader's "Read recap
 * aloud"), on the on-device voice — no network egress (invariant 1), and
 * `core-playback` is untouched.
 *
 * Scoped to the screen's nav back-stack entry (via `hiltViewModel()`), so all
 * controls on one screen share this instance: one utterance at a time, and the
 * control that started it is the one that shows "Stop". Leaving the screen
 * clears the ViewModel, which stops any utterance still playing.
 */
@HiltViewModel
class ReadAloudViewModel @Inject constructor(
    private val playback: PlaybackControllerUi,
) : ViewModel() {

    private val activeKey = MutableStateFlow<String?>(null)
    /** True from the tap until the voice has warmed and speech has begun. */
    private val warming = MutableStateFlow(false)
    private val failedKey = MutableStateFlow<String?>(null)

    private val speaking: StateFlow<Boolean> = playback.recapPlayback
        .map { it == UiRecapPlaybackState.Speaking }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<ReadAloudUiState> =
        combine(activeKey, warming, speaking, failedKey) { key, warm, speak, failed ->
            ReadAloudUiState(activeKey = key, busy = warm || speak, failedKey = failed)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ReadAloudUiState())

    private var speakJob: Job? = null

    /** Tap on the control identified by [key] whose current script is [text]. */
    fun toggle(key: String, text: String) {
        val s = state.value
        when (val action = ReadAloudSession.decide(key, s.activeKey, s.busy, text)) {
            ReadAloudAction.Stop -> stop()
            ReadAloudAction.None -> Unit
            is ReadAloudAction.Speak -> speak(key, action.text)
        }
    }

    private fun speak(key: String, text: String) {
        speakJob?.cancel()
        failedKey.value = null
        activeKey.value = key
        warming.value = true
        speakJob = viewModelScope.launch {
            try {
                // The engine is shared with fiction playback — pause the book
                // first so the two don't talk over each other (same contract as
                // ReaderViewModel.toggleRecapAloud).
                if (playback.state.first().isPlaying) playback.pause()
                playback.speakText(text)
                // speakText returns once the utterance is queued; the Speaking
                // mirror lags a beat. If it never arrives the voice couldn't be
                // activated (e.g. no voice set up yet) — tell the user rather
                // than failing silently.
                val started = withTimeoutOrNull(START_GRACE_MS) { speaking.first { it } }
                if (started == null) failedKey.value = key
            } finally {
                warming.value = false
            }
        }
    }

    fun stop() {
        speakJob?.cancel()
        speakJob = null
        warming.value = false
        activeKey.value = null
        playback.stopSpeaking()
    }

    override fun onCleared() {
        playback.stopSpeaking()
        super.onCleared()
    }

    private companion object {
        const val START_GRACE_MS = 2_000L
    }
}

@Immutable
data class ReadAloudUiState(
    val activeKey: String? = null,
    val busy: Boolean = false,
    /** Key of the control whose last read-aloud attempt never started. */
    val failedKey: String? = null,
) {
    fun isActive(key: String): Boolean = ReadAloudSession.isActive(key, activeKey, busy)
}
