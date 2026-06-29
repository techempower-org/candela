package `in`.jphe.storyvox.wear.playback

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.playback.wear.SeekPayload
import `in`.jphe.storyvox.playback.wear.TeleprompterWpmPayload
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
        _state.value = decodeState(map.getString("state"), _state.value) { t ->
            // #1032 — was a bare runCatching that silently dropped the payload.
            // A dropped state on the watch shows as stale playback info, so make
            // it greppable when it happens (malformed blob, or version skew where
            // the phone shipped a PlaybackError subtype this build can't decode).
            Log.w(TAG, "wear: dropping undecodable PlaybackState payload", t)
        }
    }

    /**
     * Send a payload-less transport command (play/pause/skip/...), returning the
     * outcome and updating [connected] from what we learned.
     */
    suspend fun send(path: String): SendResult = dispatch(path, null)

    /**
     * Issue #1031 — scrub from the wrist. Converts the 0..1 ring [fraction] to
     * an absolute position in ms against [durationMs] (the synced
     * `durationEstimateMs`) and sends it as a [SeekPayload] on
     * [PhoneWearBridge.CMD_SEEK]. Unknown-duration handling lives in
     * [SeekPayload.fromFraction] (targets 0), so this never divides by a zero
     * rail. Shares [dispatch] with [send] so a scrub-while-disconnected updates
     * [connected] and surfaces the same "Phone not connected" hint.
     */
    suspend fun sendSeek(fraction: Float, durationMs: Long): SendResult {
        val positionMs = SeekPayload.fromFraction(fraction, durationMs)
        return dispatch(PhoneWearBridge.CMD_SEEK, SeekPayload.encode(positionMs))
    }

    /**
     * Issue #1308 — teleprompter remote. Three wrist controls mirroring the
     * phone's `TeleprompterController`: enable/disable the mode, run/pause the
     * scroll, and set an absolute WPM (the watch computes the new value via
     * [TeleprompterWpmPayload.step] from the synced current, then sends it —
     * same absolute-value contract as [sendSeek]). All share [dispatch], so a
     * tap while disconnected flips [connected] and surfaces the same
     * "Phone not connected" hint as the transport controls.
     */
    suspend fun toggleTeleprompter(): SendResult =
        send(PhoneWearBridge.CMD_TELEPROMPTER_TOGGLE)

    suspend fun toggleTeleprompterScroll(): SendResult =
        send(PhoneWearBridge.CMD_TELEPROMPTER_PLAY_PAUSE)

    suspend fun sendTeleprompterWpm(wpm: Int): SendResult =
        dispatch(PhoneWearBridge.CMD_TELEPROMPTER_WPM, TeleprompterWpmPayload.encode(wpm))

    /**
     * Single send path shared by every command. A successful send is the
     * strongest evidence a node is reachable; a disconnected/failed send flips
     * [connected] to `false` so the UI greys the controls immediately. The only
     * difference between a transport tap and a seek is the [payload].
     */
    private suspend fun dispatch(path: String, payload: ByteArray?): SendResult {
        val nodes = runCatching { connectedPhoneNodes() }.getOrNull()
        val target = nodes?.let { NodeSelection.preferredTarget(it) }
        if (target == null) {
            _connected.value = false
            return SendResult.Disconnected
        }
        return runCatching {
            messageClient.sendMessage(target.id, path, payload).await()
            _connected.value = true
            SendResult.Sent
        }.getOrElse {
            _connected.value = false
            SendResult.Failed
        }
    }

    private suspend fun connectedPhoneNodes(): List<PhoneNode> =
        nodeClient.connectedNodes.await().map { PhoneNode(id = it.id, isNearby = it.isNearby) }

    companion object {
        private const val TAG = "WearPlaybackBridge"

        /**
         * Decode a phone-published [PlaybackState] JSON blob, falling back to the
         * last-good [current] state on any failure (#1032).
         *
         * Pure and instance-free so the decode contract can be unit-tested without
         * GMS `DataItem`/`DataClient` (the seam pattern, mirroring [NodeSelection]).
         * Returns [current] unchanged when:
         *  - [raw] is null (no "state" key on the DataItem), or
         *  - the JSON is malformed / truncated, or
         *  - it carries an unknown sealed-[PlaybackError] discriminator (version
         *    skew: the phone shipped an error subtype this watch build predates) —
         *    kotlinx.serialization throws on the unknown type even with
         *    `ignoreUnknownKeys`, so we must catch rather than crash the watch.
         *
         * [onError] is invoked with the decode failure so the caller can log it
         * (the previous `consume()` swallowed failures silently).
         */
        internal inline fun decodeState(
            raw: String?,
            current: PlaybackState,
            onError: (Throwable) -> Unit = {},
        ): PlaybackState {
            if (raw == null) return current
            return runCatching { DECODE_JSON.decodeFromString<PlaybackState>(raw) }
                .getOrElse { onError(it); current }
        }

        /** Matches [in.jphe.storyvox.playback.wear.PhoneWearBridge]'s encoder config. */
        @PublishedApi
        internal val DECODE_JSON = Json { ignoreUnknownKeys = true }
    }
}
