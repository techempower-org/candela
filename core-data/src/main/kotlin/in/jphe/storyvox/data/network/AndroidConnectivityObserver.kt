package `in`.jphe.storyvox.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [ConnectivityObserver] backed by [ConnectivityManager.registerDefaultNetworkCallback]
 * (issue #786).
 *
 * Registered once for the process lifetime (`@Singleton`); the default-network
 * callback follows whichever transport the system is actually routing through,
 * which is exactly the signal the UI wants — not "is *any* network up" but "can
 * the active default route reach the internet."
 *
 * The backing [MutableStateFlow] is seeded synchronously in `init` from the
 * current active network so the first collector sees a real value immediately
 * rather than [ConnectivityState.Offline] until the first async callback lands.
 *
 * Metered detection uses [NetworkCapabilities.NET_CAPABILITY_NOT_METERED]: its
 * absence means the transport is metered (cellular, or a Wi-Fi the user flagged
 * as metered). This feeds the `requireUnmetered` plumbing in
 * `ChapterDownloadScheduler` per the issue's PR 4.
 */
@Singleton
class AndroidConnectivityObserver @Inject constructor(
    @ApplicationContext context: Context,
) : ConnectivityObserver {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(currentState())
    override val state: StateFlow<ConnectivityState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _state.value = currentState()
        }

        override fun onLost(network: Network) {
            // onLost fires for the network that dropped; re-derive from the
            // manager rather than assuming Offline, since another transport
            // (e.g. cellular after Wi-Fi drops) may already be the default.
            _state.value = currentState()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities,
        ) {
            // Metered <-> unmetered transitions and validation changes arrive
            // here without an onAvailable/onLost pair (e.g. captive portal
            // sign-in completing). Map straight from the fresh capabilities.
            _state.value = capabilities.toConnectivityState()
        }
    }

    init {
        // A capabilities-bearing request so onCapabilitiesChanged delivers the
        // metered flag; registerDefaultNetworkCallback would also work but the
        // explicit request keeps the metered signal first-class.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm?.registerNetworkCallback(request, callback)
    }

    private fun currentState(): ConnectivityState {
        val active = cm?.activeNetwork ?: return ConnectivityState.Offline
        val caps = cm.getNetworkCapabilities(active) ?: return ConnectivityState.Offline
        return caps.toConnectivityState()
    }

    private fun NetworkCapabilities.toConnectivityState(): ConnectivityState {
        val hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (!hasInternet || !validated) return ConnectivityState.Offline
        val unmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (unmetered) ConnectivityState.Connected else ConnectivityState.ConnectedMetered
    }
}
