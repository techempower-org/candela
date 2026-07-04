package `in`.jphe.storyvox.feature.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.impact.ImpactReporter
import `in`.jphe.storyvox.data.repository.impact.ImpactShareData
import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import `in`.jphe.storyvox.data.repository.stats.ListeningStatsRepository
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Issue #1235 — view-model for the listening-statistics dashboard.
 *
 * One-shot load on construction (and on [refresh]). The repository
 * snapshot is a handful of cheap aggregate queries, so there's no need
 * for a long-lived reactive subscription; re-opening the screen mints a
 * fresh ViewModel and re-loads.
 */
@Immutable
sealed interface ListeningStatsUiState {
    /** Initial load — the screen shows a spinner. */
    data object Loading : ListeningStatsUiState

    /** Snapshot ready. [ListeningStats.hasData] decides full UI vs. empty state. */
    data class Loaded(val stats: ListeningStats) : ListeningStatsUiState
}

@HiltViewModel
class ListeningStatsViewModel @Inject constructor(
    private val repository: ListeningStatsRepository,
    private val impactReporter: ImpactReporter,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ListeningStatsUiState>(ListeningStatsUiState.Loading)
    val uiState: StateFlow<ListeningStatsUiState> = _uiState.asStateFlow()

    /**
     * Issue #1463 — the current opt-in impact-share payload for the loaded
     * snapshot (coarse delta since last share). Null while loading or if it
     * couldn't be computed; the card is hidden unless there's something to
     * share (or the user already shared this period).
     */
    private val _impact = MutableStateFlow<ImpactShareData?>(null)
    val impact: StateFlow<ImpactShareData?> = _impact.asStateFlow()

    /** #1265 — the in-flight load, cancelled before each new [refresh]. */
    private var refreshJob: Job? = null

    /** The most recently loaded snapshot, reused to recompute the share
     *  payload after a share without re-querying the DB. */
    private var lastStats: ListeningStats = ListeningStats.EMPTY

    init {
        refresh()
    }

    fun refresh() {
        // #1265 — cancel any in-flight load first so rapid re-entry can't
        // overlap DB queries or let a stale snapshot land after a newer one.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.value = ListeningStatsUiState.Loading
            // A query failure on a stats screen should degrade to the
            // empty state, never crash the app — the data is informational.
            val stats = runCatching { repository.snapshot() }
                .getOrDefault(ListeningStats.EMPTY)
            lastStats = stats
            _uiState.value = ListeningStatsUiState.Loaded(stats)
            // #1463 — the impact share payload is best-effort; a failure here
            // must never break the dashboard, so it degrades to "no card".
            _impact.value = runCatching { impactReporter.shareFor(stats) }.getOrNull()
        }
    }

    /**
     * Issue #1463 — persist that the current payload was shared, then
     * recompute so the card reflects the new baseline (delta now zero,
     * "last shared" set to this period).
     */
    fun onShared() {
        val shared = _impact.value ?: return
        viewModelScope.launch {
            runCatching { impactReporter.markShared(shared) }
            _impact.value = runCatching { impactReporter.shareFor(lastStats) }.getOrNull()
        }
    }
}
