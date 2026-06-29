package `in`.jphe.storyvox.feature.stats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.stats.ListeningStats
import `in`.jphe.storyvox.data.repository.stats.ListeningStatsRepository
import javax.inject.Inject
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
) : ViewModel() {

    private val _uiState = MutableStateFlow<ListeningStatsUiState>(ListeningStatsUiState.Loading)
    val uiState: StateFlow<ListeningStatsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = ListeningStatsUiState.Loading
            // A query failure on a stats screen should degrade to the
            // empty state, never crash the app — the data is informational.
            val stats = runCatching { repository.snapshot() }
                .getOrDefault(ListeningStats.EMPTY)
            _uiState.value = ListeningStatsUiState.Loaded(stats)
        }
    }
}
