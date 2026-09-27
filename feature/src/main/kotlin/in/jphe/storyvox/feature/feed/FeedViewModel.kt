package `in`.jphe.storyvox.feature.feed

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import `in`.jphe.storyvox.data.repository.FollowsRepository
import `in`.jphe.storyvox.data.source.FictionSourceIdResolver
import `in`.jphe.storyvox.data.source.plugin.SourcePluginRegistry
import `in`.jphe.storyvox.playback.briefing.BriefingQueueController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class FeedUiState(
    val loading: Boolean = true,
    val rows: List<FeedRow> = emptyList(),
    /** Index into [rows] of the item playing now, or -1. */
    val playingIndex: Int = -1,
)

/**
 * #1675 — the listening feed. HyperTexting-style: everything new from what the
 * user follows, newest first, no ranking. Tapping a card plays it and the
 * feed keeps going down the list on its own.
 *
 * Data is the existing unread-chapter query (library fictions, unread,
 * `publishedAt DESC`) that already powers Android Auto's rail; playback reuses
 * the Morning Briefing's cross-fiction queue ([BriefingQueueController]) in
 * per-item mode so each card is one item, not a whole feed.
 */
@HiltViewModel
class FeedViewModel @Inject constructor(
    private val follows: FollowsRepository,
    private val registry: SourcePluginRegistry,
    private val queue: BriefingQueueController,
) : ViewModel() {

    private val loading = MutableStateFlow(true)
    private val rows = MutableStateFlow<List<FeedRow>>(emptyList())

    val state: StateFlow<FeedUiState> = combine(loading, rows, queue.session) { l, r, s ->
        FeedUiState(
            loading = l,
            rows = r,
            playingIndex = FeedLogic.playingIndex(
                rows = r,
                queueChapterIds = s?.takeUnless { it.finished }?.items?.map { it.chapterId },
                currentChapterId = s?.current?.chapterId,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedUiState())

    init {
        refresh()
    }

    /** Re-read the unread list from Room (new items land via the existing background poll). */
    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            val labels = registry.all.associate { it.id to it.displayName }
            rows.value = runCatching {
                FeedLogic.rows(
                    unread = follows.unreadChapters(FeedLogic.FEED_LIMIT),
                    sourceIdOf = FictionSourceIdResolver::resolveByShape,
                    labelOf = labels::get,
                )
            }.getOrDefault(emptyList())
            loading.value = false
        }
    }

    /** Play [index] and keep auto-advancing down the feed from there. */
    fun playFrom(index: Int) {
        val current = rows.value
        if (index !in current.indices) return
        viewModelScope.launch {
            queue.startWith(
                items = FeedLogic.queue(current),
                startIndex = index,
                advanceOnChapterDone = true,
            )
        }
    }

    /** Stop auto-advancing (the current item keeps playing until paused). */
    fun stopAutoAdvance() = queue.stop()
}
