package `in`.jphe.storyvox.data.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Observes Android network reachability and exposes it as a hot [StateFlow]
 * (issue #786). Before this, storyvox had zero production awareness of network
 * state — Browse fired a source request, OkHttp failed to open a socket, and
 * the user waited out the full timeout before seeing "Connection lost." A
 * StateFlow lets the foreground UI fail-fast with an offline banner in ~50ms.
 *
 * The flow is conflated and always has a value (the implementation seeds it
 * from the current network on construction), so collectors get an immediate
 * emission rather than waiting for the first [android.net.ConnectivityManager]
 * callback.
 */
interface ConnectivityObserver {
    val state: StateFlow<ConnectivityState>
}
