package `in`.jphe.storyvox.playback.wear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.SleepTimerMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone-side counterpart of [in.jphe.storyvox.wear.playback.WearPlaybackBridge].
 *
 * - Publishes [PlaybackState] to a DataItem at `/playback/state` so the watch can
 *   hydrate immediately on boot.
 * - Listens for `/playback/cmd/...` MessageClient messages and translates them into
 *   [PlaybackController] calls.
 *
 * Started by [in.jphe.storyvox.playback.StoryvoxPlaybackService.onCreate];
 * stopped in `onDestroy`.
 */
@Singleton
class PhoneWearBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: PlaybackController,
) : MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        messageClient.addListener(this)
        scope.launch {
            controller.state.collectLatest { state -> publishState(state) }
        }
    }

    fun stop() {
        messageClient.removeListener(this)
        scope.cancel()
    }

    private suspend fun publishState(state: PlaybackState) {
        runCatching {
            val req = PutDataMapRequest.create(PATH_STATE).apply {
                dataMap.putString("state", json.encodeToString(state))
                dataMap.putLong("ts", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            dataClient.putDataItem(req).await()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        scope.launch {
            when (event.path) {
                CMD_PLAY -> controller.resume()
                CMD_PAUSE -> controller.pause()
                CMD_TOGGLE -> controller.togglePlayPause()
                CMD_SKIP_FWD -> controller.skipForward30s()
                CMD_SKIP_BACK -> controller.skipBack30s()
                CMD_NEXT_CH -> controller.nextChapter()
                CMD_PREV_CH -> controller.previousChapter()
                CMD_SLEEP_15 -> controller.startSleepTimer(SleepTimerMode.Duration(15))
                CMD_SLEEP_OFF -> controller.cancelSleepTimer()
                // Issue #1031 — the circular scrubber sends a target position
                // (ms) in the message payload. A malformed/absent payload
                // decodes to null and is ignored (no blind seek-to-zero).
                CMD_SEEK -> SeekPayload.decode(event.data)?.let { controller.seekToPositionMs(it) }
            }
        }
    }

    companion object {
        const val PATH_STATE = "/playback/state"
        const val CMD_PLAY = "/playback/cmd/play"
        const val CMD_PAUSE = "/playback/cmd/pause"
        const val CMD_TOGGLE = "/playback/cmd/toggle"
        const val CMD_SKIP_FWD = "/playback/cmd/skipFwd"
        const val CMD_SKIP_BACK = "/playback/cmd/skipBack"
        const val CMD_NEXT_CH = "/playback/cmd/nextCh"
        const val CMD_PREV_CH = "/playback/cmd/prevCh"
        const val CMD_SLEEP_15 = "/playback/cmd/sleep15"
        const val CMD_SLEEP_OFF = "/playback/cmd/sleepOff"

        /**
         * Issue #1031 — scrub from the wrist. Unlike the payload-less
         * transport commands above, this message carries an 8-byte
         * [SeekPayload] (absolute target position in ms).
         */
        const val CMD_SEEK = "/playback/cmd/seek"
    }
}
