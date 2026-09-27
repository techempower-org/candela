package `in`.jphe.storyvox.feature.techempower.readaloud

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.feature.api.PlaybackControllerUi
import `in`.jphe.storyvox.feature.api.UiRecapPlaybackState
import `in`.jphe.storyvox.feature.api.UiSpeakOutcome
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
    /** Key of the control whose last attempt failed, and why (#1776). */
    private val failed = MutableStateFlow<Pair<String, ReadAloudFailure>?>(null)

    private val speaking: StateFlow<Boolean> = playback.recapPlayback
        .map { it == UiRecapPlaybackState.Speaking }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val state: StateFlow<ReadAloudUiState> =
        combine(activeKey, warming, speaking, failed) { key, warm, speak, fail ->
            ReadAloudUiState(
                activeKey = key,
                busy = warm || speak,
                failedKey = fail?.first,
                failure = fail?.second,
            )
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
        failed.value = null
        activeKey.value = key
        warming.value = true
        speakJob = viewModelScope.launch {
            try {
                // The engine is shared with fiction playback — pause the book
                // first so the two don't talk over each other (same contract as
                // ReaderViewModel.toggleRecapAloud).
                if (playback.state.first().isPlaying) playback.pause()
                // #1776 — starts the playback service on a cold launch, waits
                // for the engine to bind, and reports how it went, so a
                // failure is a real reason rather than a timeout guess.
                val outcome = playback.speakTextForOutcome(text)
                val failure = ReadAloudSession.failureFor(outcome)
                if (failure != null) {
                    failed.value = key to failure
                } else if (outcome == UiSpeakOutcome.Started) {
                    // The Speaking mirror lags the engine by a beat; hold the
                    // busy state across that gap so the button doesn't flicker
                    // back to "Read aloud". A very short utterance may already
                    // have finished — that's not a failure.
                    withTimeoutOrNull(MIRROR_LAG_MS) { speaking.first { it } }
                }
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
        const val MIRROR_LAG_MS = 1_000L
    }
}

@Immutable
data class ReadAloudUiState(
    val activeKey: String? = null,
    val busy: Boolean = false,
    /** Key of the control whose last read-aloud attempt never started. */
    val failedKey: String? = null,
    /** Why [failedKey]'s attempt failed (#1776). */
    val failure: ReadAloudFailure? = null,
) {
    fun isActive(key: String): Boolean = ReadAloudSession.isActive(key, activeKey, busy)
}
