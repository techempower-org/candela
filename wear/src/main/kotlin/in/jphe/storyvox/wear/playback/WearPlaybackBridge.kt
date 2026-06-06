package `in`.jphe.storyvox.wear.playback

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * Watch-side counterpart of [in.jphe.storyvox.playback.wear.PhoneWearBridge].
 *
 * - Subscribes to `/playback/state` DataItem updates from the phone, decodes the
 *   JSON-encoded [PlaybackState], and exposes it as a [StateFlow].
 * - Tracks whether a phone node is reachable via [connected], refreshed on
 *   [start]/[refreshConnectivity] and updated reactively from every [send].
 * - Sends transport commands to the phone via [com.google.android.gms.wearable.MessageClient],
 *   returning a [SendResult] so the caller can surface a "Phone not connected"
 *   hint instead of silently dropping the tap (#1030).
 *
 * Lifecycle bound to [in.jphe.storyvox.wear.WearApp] / its NowPlaying composable.
 */
class WearPlaybackBridge(private val context: Context) : DataClient.OnDataChangedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * Whether a phone node is currently reachable. Starts `true` (optimistic, so
     * controls aren't greyed for the split second before the first node query
     * resolves) and is corrected by [refreshConnectivity] and every [send].
     */
    private val _connected = MutableStateFlow(true)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun start() {
        dataClient.addListener(this, android.net.Uri.parse("wear://*/playback"), DataClient.FILTER_PREFIX)
        // Hydrate immediately from the latest cached DataItem
        scope.launch {
            runCatching {
                dataClient.dataItems.await().forEach { item ->
                    if (item.uri.path == PhoneWearBridge.PATH_STATE) consume(item)
                }
            }
        }
        refreshConnectivity()
    }

    fun stop() {
        dataClient.removeListener(this)
        scope.cancel()
    }

    /**
     * Re-query reachable nodes and update [connected]. Call on resume so the UI
     * reflects a phone that came back into range while the watch screen was off.
     */
    fun refreshConnectivity() {
        scope.launch {
            val nodes = runCatching { connectedPhoneNodes() }.getOrNull()
            // A query failure (GMS unavailable) is left as-is rather than forced
            // to disconnected — the next send is the authoritative signal.
            if (nodes != null) _connected.value = NodeSelection.isConnected(nodes)
        }
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.dataItem.uri.path == PhoneWearBridge.PATH_STATE) consume(e.dataItem)
        }
    }

    private fun consume(item: DataItem) {
        val map = DataMapItem.fromDataItem(item).dataMap
        val raw = map.getString("state") ?: return
        runCatching { _state.value = json.decodeFromString<PlaybackState>(raw) }
    }

    /**
     * Send a transport command, returning the outcome and updating [connected]
     * from what we learned. A successful send is the strongest evidence a node
     * is reachable; a disconnected/failed send flips [connected] to `false` so
     * the UI greys the controls immediately.
     */
    suspend fun send(path: String): SendResult {
        val nodes = runCatching { connectedPhoneNodes() }.getOrNull()
        val target = nodes?.let { NodeSelection.preferredTarget(it) }
        if (target == null) {
            _connected.value = false
            return SendResult.Disconnected
        }
        return runCatching {
            messageClient.sendMessage(target.id, path, null).await()
            _connected.value = true
            SendResult.Sent
        }.getOrElse {
            _connected.value = false
            SendResult.Failed
        }
    }

    private suspend fun connectedPhoneNodes(): List<PhoneNode> =
        nodeClient.connectedNodes.await().map { PhoneNode(id = it.id, isNearby = it.isNearby) }
}
