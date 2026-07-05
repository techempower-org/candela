package `in`.jphe.storyvox.feature.browse

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.feature.api.BrowsePaginator
import `in`.jphe.storyvox.feature.api.UiFiction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Page-by-page accumulator. Mutex-guarded so the grid's near-end
 * `LaunchedEffect` can call `loadNext()` freely without races. Failures
 * surface in [error] without bumping the page counter, so the user
 * scrolling back near-end after a transient blip retries the same page
 * transparently.
 *
 * Pulled out of `:app/AppBindings.kt` (where it was an inline anonymous
 * implementation held by a lambda factory) so its state machine is
 * unit-testable from the JVM. Lives in `:feature` rather than `:core-data`
 * because [BrowsePaginator]'s contract + [UiFiction] both live in
 * `:feature.api`, and pulling the impl up to `:core-data` would invert
 * the existing `:feature → :core-data` dependency direction.
 */
class RealBrowsePaginator(
    private val fetchPage: suspend (page: Int) -> FictionResult<ListPage<FictionSummary>>,
) : BrowsePaginator {
    private val _items = MutableStateFlow<List<UiFiction>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    private val _isAppending = MutableStateFlow(false)
    private val _hasMore = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _authRequired = MutableStateFlow(false)
    private var nextPage = 1
    private val mutex = Mutex()

    override val items: Flow<List<UiFiction>> = _items.asStateFlow()
    override val isLoading: Flow<Boolean> = _isLoading.asStateFlow()
    override val isAppending: Flow<Boolean> = _isAppending.asStateFlow()
    override val hasMore: Flow<Boolean> = _hasMore.asStateFlow()
    override val error: Flow<String?> = _error.asStateFlow()
    override val authRequired: Flow<Boolean> = _authRequired.asStateFlow()

    override suspend fun loadNext() = mutex.withLock {
        if (!_hasMore.value || _isAppending.value || _isLoading.value) return@withLock
        val isFirst = nextPage == 1
        if (isFirst) _isLoading.value = true else _isAppending.value = true
        try {
            when (val res = fetchPage(nextPage)) {
                is FictionResult.Success -> {
                    _items.value = _items.value + res.value.items.map(::toUiFiction)
                    _hasMore.value = res.value.hasNext
                    nextPage++
                    _error.value = null
                    _authRequired.value = false
                }
                is FictionResult.Failure -> {
                    android.util.Log.w("storyvox", "Browse fetch failed: ${res.message}", res.cause)
                    _error.value = res.message
                    // #1588 — preserve the failure axis the String error erases,
                    // so an unconnected source can surface a "connect" CTA while a
                    // transient network/server error falls through to the generic
                    // error state.
                    _authRequired.value = res is FictionResult.AuthRequired
                    // do NOT bump nextPage — next near-end retries the same page
                }
            }
        } finally {
            _isLoading.value = false
            _isAppending.value = false
        }
    }

    override suspend fun refresh() = mutex.withLock {
        _items.value = emptyList()
        _hasMore.value = true
        _error.value = null
        _authRequired.value = false
        nextPage = 1
    }
}

fun toUiFiction(s: FictionSummary): UiFiction = UiFiction(
    id = s.id,
    title = s.title,
    author = s.author,
    coverUrl = s.coverUrl,
    rating = s.rating ?: 0f,
    chapterCount = s.chapterCount ?: 0,
    isOngoing = s.status == FictionStatus.ONGOING,
    synopsis = s.description.orEmpty(),
    sourceId = s.sourceId,
    isFollowedRemote = s.followedRemotely,
    sourceSupportsFollow = s.supportsFollow,
)
