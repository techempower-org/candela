package `in`.jphe.storyvox.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test double for [ConnectivityObserver] (issue #786). Lets viewmodel and
 * screen tests drive offline/online/metered transitions deterministically
 * without Robolectric or a live [android.net.ConnectivityManager].
 */
class FakeConnectivityObserver(
    initial: ConnectivityState = ConnectivityState.Connected,
) : ConnectivityObserver {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    fun emit(next: ConnectivityState) {
        _state.value = next
    }
}
