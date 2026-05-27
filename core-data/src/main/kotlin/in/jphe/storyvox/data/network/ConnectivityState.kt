package `in`.jphe.storyvox.data.network

/**
 * Coarse network reachability as observed by [ConnectivityObserver].
 *
 * [ConnectedMetered] is split out from [Connected] so the existing
 * `requireUnmetered` plumbing in `ChapterDownloadScheduler` can be derived
 * from live state (issue #786) rather than the static setting alone — a
 * worker can fail its `NetworkType.UNMETERED` constraint faster when the UI
 * already knows the active transport is metered.
 */
enum class ConnectivityState {
    Connected,
    ConnectedMetered,
    Offline,
    ;

    /** True for any state that can reach the network, metered or not. */
    val isOnline: Boolean get() = this != Offline
}
