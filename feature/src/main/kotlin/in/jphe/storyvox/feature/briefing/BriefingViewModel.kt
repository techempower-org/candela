package `in`.jphe.storyvox.feature.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.briefing.BriefingConfig
import `in`.jphe.storyvox.playback.briefing.BriefingQueueController
import `in`.jphe.storyvox.playback.briefing.BriefingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * #1467 — drives the Morning Briefing entry point.
 *
 * Thin: the durable queue state lives in the [BriefingQueueController]
 * singleton (so the episode survives this screen leaving composition), and the
 * ViewModel just exposes it and triggers an on-demand build with the default
 * config. Slice 2 will swap [BriefingConfig.DEFAULT] for a persisted user
 * config without changing this call site.
 */
@HiltViewModel
class BriefingViewModel @Inject constructor(
    private val queue: BriefingQueueController,
) : ViewModel() {

    /** The live briefing session (null when none is playing). */
    val session: StateFlow<BriefingSession?> = queue.session

    private val _building = MutableStateFlow(false)
    /** True while the queue is being assembled (network fetches in flight). */
    val building: StateFlow<Boolean> = _building.asStateFlow()

    private val _emptyResult = MutableStateFlow(false)
    /** True when the last build resolved zero playable items. */
    val emptyResult: StateFlow<Boolean> = _emptyResult.asStateFlow()

    /** Assemble today's briefing and start playing it as one continuous episode. */
    fun buildAndPlay() {
        if (_building.value) return
        viewModelScope.launch {
            _building.value = true
            _emptyResult.value = false
            val started = runCatching { queue.start(BriefingConfig.DEFAULT) }.getOrDefault(false)
            _building.value = false
            _emptyResult.value = !started
        }
    }

    /** Stop the briefing and clear the session. */
    fun stop() = queue.stop()
}
