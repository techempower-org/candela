package `in`.jphe.storyvox.wear.playback

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.SleepTimerMode
import `in`.jphe.storyvox.playback.extrapolatedScrubProgress
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.playback.wear.SeekPayload
import `in`.jphe.storyvox.playback.wear.SleepPayload
import `in`.jphe.storyvox.playback.wear.SpeedPayload
import `in`.jphe.storyvox.playback.wear.TeleprompterWpmPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
     * Scrubber progress (0..1), locally extrapolated between the phone's ~1Hz
     * beacons so the ring animates smoothly instead of stepping once per push.
     * Recomputed on every beacon and on a [DISPLAY_TICK_MS] cadence from the
     * watch's own monotonic clock — see [recomputeDerived]. Collect this for the
     * scrubber instead of [PlaybackState.scrubProgress] on [state].
     */
    private val _displayProgress = MutableStateFlow(0f)
    val displayProgress: StateFlow<Float> = _displayProgress.asStateFlow()

    /**
     * True when the state says we're playing but no beacon has arrived for
     * [STALE_THRESHOLD_MS] — the phone app went away mid-listen even though a
     * node may still be nominally reachable. The UI surfaces this as
     * "Reconnecting…". A paused/idle state never goes stale (it legitimately
     * gets no beacons).
     */
    private val _stale = MutableStateFlow(false)
    val stale: StateFlow<Boolean> = _stale.asStateFlow()

    /** Watch-monotonic ([SystemClock.elapsedRealtime]) timestamp of the last
     *  successfully-decoded beacon. The extrapolation/staleness clock — NEVER
     *  the phone's publish time, which is on a different device's wall clock. */
    private var lastBeaconElapsedMs: Long = SystemClock.elapsedRealtime()

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
        // Drive the smooth scrubber + staleness between beacons. Bound to the
        // bridge scope, so it dies with [stop] when the watch screen goes away —
        // no background ticking while the UI isn't visible.
        scope.launch {
            while (true) {
                delay(DISPLAY_TICK_MS)
                recomputeDerived()
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
        var decoded = true
        _state.value = decodeState(map.getString("state"), _state.value) { t ->
            // #1032 — was a bare runCatching that silently dropped the payload.
            // A dropped state on the watch shows as stale playback info, so make
            // it greppable when it happens (malformed blob, or version skew where
            // the phone shipped a PlaybackError subtype this build can't decode).
            decoded = false
            Log.w(TAG, "wear: dropping undecodable PlaybackState payload", t)
        }
        // Only a clean decode counts as a live beacon — a malformed payload
        // mustn't reset the staleness clock and mask a real connection problem.
        if (decoded) lastBeaconElapsedMs = SystemClock.elapsedRealtime()
        recomputeDerived()
    }

    /**
     * Re-extrapolate [displayProgress] and re-evaluate [stale] from the current
     * [state] and the watch-local time since the last beacon. Called on every
     * beacon and on the [DISPLAY_TICK_MS] ticker; both write from the bridge
     * scope (Main), so no synchronisation is needed.
     */
    private fun recomputeDerived() {
        val s = _state.value
        val elapsed = SystemClock.elapsedRealtime() - lastBeaconElapsedMs
        _displayProgress.value = s.extrapolatedScrubProgress(elapsed)
        _stale.value = isWearStateStale(s.isPlaying, elapsed, STALE_THRESHOLD_MS)
    }

    /**
     * Send a payload-less transport command (play/pause/skip/...), returning the
     * outcome and updating [connected] from what we learned.
     */
    suspend fun send(path: String): SendResult = dispatch(path, null)

    /**
     * Wear companion — Retry after a surfaced playback error. Asks the phone to
     * re-load the current chapter (clearing its error band). Payload-less; shares
     * [dispatch] so a tap while disconnected flips [connected] and surfaces the
     * same "Phone not connected" hint as the transport controls.
     */
    suspend fun sendRetry(): SendResult = send(PhoneWearBridge.CMD_RETRY)

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
     * Sleep timer from the wrist. [sendSleepSet] arms a 15/30/45-min or
     * end-of-chapter timer (encoded via [SleepPayload]); [sendSleepCancel]
     * clears it. The remaining time syncs back through
     * `PlaybackState.sleepTimerRemainingMs` like any other playback state, so
     * the now-playing countdown needs no separate channel. Both share
     * [dispatch]/[send], so a tap while disconnected flips [connected] and
     * surfaces the same "Phone not connected" hint as the transport controls.
     */
    suspend fun sendSleepSet(mode: SleepTimerMode): SendResult =
        dispatch(PhoneWearBridge.CMD_SLEEP_SET, SleepPayload.encode(mode))

    suspend fun sendSleepCancel(): SendResult =
        send(PhoneWearBridge.CMD_SLEEP_CANCEL)

    /**
     * Playback-speed remote — same absolute-value contract as [sendSeek] /
     * [sendTeleprompterWpm]: the watch computes the next speed from the synced
     * `PlaybackState.speed` via [SpeedPayload.step] and sends the absolute
     * value. The phone routes it to the per-fiction speed store (#1231).
     */
    suspend fun sendSetSpeed(speed: Float): SendResult =
        dispatch(PhoneWearBridge.CMD_SET_SPEED, SpeedPayload.encode(speed))

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

        /** Local re-extrapolation cadence for the scrubber. 4Hz is smooth for a
         *  slowly-advancing ring and trivial while the screen is on; the ticker
         *  stops with the bridge when the screen turns off. */
        private const val DISPLAY_TICK_MS = 250L

        /** No beacon for this long while the state says playing ⇒ "Reconnecting…".
         *  Comfortably above the phone's ~1Hz beacon so normal jitter doesn't
         *  trip it, low enough to notice a real drop within a few seconds. */
        private const val STALE_THRESHOLD_MS = 5_000L

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

/**
 * Has the phone gone quiet mid-listen? True only when the state says we're
 * [isPlaying] AND no beacon has landed for [staleThresholdMs] (both measured on
 * the WATCH's own monotonic clock — see [WearPlaybackBridge.lastBeaconElapsedMs]).
 *
 * Gating on `isPlaying` is the whole point: a paused or idle chapter
 * legitimately receives no beacons (the phone falls silent once playback stops
 * — see [shouldPublishWearState]), so it must NOT read as "Reconnecting…".
 *
 * Extracted as a top-level function so the rule is unit-testable without GMS,
 * matching [WearPlaybackBridge.decodeState] and `NodeSelection`.
 */
internal fun isWearStateStale(
    isPlaying: Boolean,
    elapsedSinceBeaconMs: Long,
    staleThresholdMs: Long,
): Boolean = isPlaying && elapsedSinceBeaconMs >= staleThresholdMs
