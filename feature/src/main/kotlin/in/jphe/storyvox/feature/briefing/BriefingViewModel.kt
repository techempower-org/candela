package `in`.jphe.storyvox.feature.briefing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.briefing.BriefingPlanner
import `in`.jphe.storyvox.data.briefing.BriefingSchedule
import `in`.jphe.storyvox.data.briefing.BriefingSettings
import `in`.jphe.storyvox.data.briefing.PrebuiltBriefing
import `in`.jphe.storyvox.playback.briefing.BriefingPrebuildScheduler
import `in`.jphe.storyvox.playback.briefing.BriefingQueueController
import `in`.jphe.storyvox.playback.briefing.BriefingSession
import `in`.jphe.storyvox.playback.briefing.BriefingSettingsStore
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * #1467 — drives the Morning Briefing screen.
 *
 * Durable queue state lives in the [BriefingQueueController] singleton (so the
 * episode survives this screen leaving composition); the picker + schedule
 * live in [BriefingSettingsStore]. Play uses today's prebuilt queue when the
 * daily worker already assembled one for the current picker, else builds live.
 */
@HiltViewModel
class BriefingViewModel @Inject constructor(
    private val queue: BriefingQueueController,
    private val store: BriefingSettingsStore,
    private val prebuildScheduler: BriefingPrebuildScheduler,
) : ViewModel() {

    /** The live briefing session (null when none is playing). */
    val session: StateFlow<BriefingSession?> = queue.session

    /** Picker + schedule, normalized against the source catalog. */
    val settings: StateFlow<BriefingSettings> = store.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BriefingSettings())

    /** Today's prebuilt queue for the current picker, or null (play will build live). */
    val readyToday: StateFlow<PrebuiltBriefing?> = combine(store.settings, store.prebuilt) { s, p ->
        p?.takeIf { BriefingPlanner.freshPrebuilt(it, s.config, today()) != null }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _building = MutableStateFlow(false)
    /** True while the queue is being assembled (network fetches in flight). */
    val building: StateFlow<Boolean> = _building.asStateFlow()

    private val _emptyResult = MutableStateFlow(false)
    /** True when the last build resolved zero playable items. */
    val emptyResult: StateFlow<Boolean> = _emptyResult.asStateFlow()

    /** Play today's briefing as one continuous episode (prebuilt if fresh, else built now). */
    fun buildAndPlay() {
        if (_building.value) return
        viewModelScope.launch {
            _building.value = true
            _emptyResult.value = false
            val started = runCatching {
                val current = store.current()
                val prebuilt = BriefingPlanner.freshPrebuilt(store.currentPrebuilt(), current.config, today())
                if (prebuilt != null) {
                    queue.startWith(prebuilt, advanceOnChapterDone = true)
                } else {
                    queue.start(current.config)
                }
            }.getOrDefault(false)
            _building.value = false
            _emptyResult.value = !started
        }
    }

    /** Stop the briefing and clear the session. */
    fun stop() = queue.stop()

    fun setSourceEnabled(sourceId: String, enabled: Boolean) = updateConfig { s ->
        s.copy(config = s.config.copy(sources = s.config.sources.map { if (it.sourceId == sourceId) it.copy(enabled = enabled) else it }))
    }

    fun setSourceCount(sourceId: String, count: Int) = updateConfig { s ->
        val clamped = count.coerceIn(BriefingPlanner.MIN_COUNT, BriefingPlanner.MAX_COUNT)
        s.copy(config = s.config.copy(sources = s.config.sources.map { if (it.sourceId == sourceId) it.copy(count = clamped) else it }))
    }

    fun setScheduleEnabled(enabled: Boolean) = updateSchedule { it.copy(enabled = enabled) }

    fun setScheduleTime(hour: Int, minute: Int) = updateSchedule { it.copy(hour = hour, minute = minute) }

    private fun updateConfig(transform: (BriefingSettings) -> BriefingSettings) {
        viewModelScope.launch { store.update(transform) }
    }

    private fun updateSchedule(transform: (BriefingSchedule) -> BriefingSchedule) {
        viewModelScope.launch {
            val next = store.update { it.copy(schedule = transform(it.schedule)) }
            runCatching { prebuildScheduler.apply(next.schedule) }
        }
    }

    private fun today(): Long = BriefingPlanner.epochDay(System.currentTimeMillis(), ZoneId.systemDefault())
}
