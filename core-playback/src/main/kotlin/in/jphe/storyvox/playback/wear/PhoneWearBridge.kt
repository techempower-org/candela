package `in`.jphe.storyvox.playback.wear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.repository.stats.ListeningStatsRepository
import `in`.jphe.storyvox.playback.PlaybackController
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.RecordingController
import `in`.jphe.storyvox.playback.SleepTimerMode
import `in`.jphe.storyvox.playback.TeleprompterController
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val teleprompterController: TeleprompterController,
    private val recordingController: RecordingController,
    private val statsRepository: ListeningStatsRepository,
) : MessageClient.OnMessageReceivedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataClient by lazy { Wearable.getDataClient(context) }
    private val messageClient by lazy { Wearable.getMessageClient(context) }
    private val json = Json { ignoreUnknownKeys = true }

    fun start() {
        messageClient.addListener(this)
        scope.launch {
            // #1308 — fold the teleprompter state (separate TeleprompterController
            // singleton, #1320) into the published PlaybackState so the watch
            // reflects it over the existing /playback/state sync. Any teleprompter
            // change re-publishes, same as a playback change.
            // #1367 (Wear PR1) — also fold RecordingController state in, so the
            // watch reflects recording over the same /playback/state sync.
            // Two nested 4-arg combines keep each within the typed overload
            // limit (a single 7-flow combine would fall to the untyped vararg).
            val withTeleprompter = combine(
                controller.state,
                teleprompterController.enabled,
                teleprompterController.playing,
                teleprompterController.wpm,
            ) { state, tpEnabled, tpPlaying, tpWpm ->
                state.copy(
                    teleprompterEnabled = tpEnabled,
                    teleprompterPlaying = tpPlaying,
                    teleprompterWpm = tpWpm,
                )
            }
            combine(
                withTeleprompter,
                recordingController.armed,
                recordingController.recording,
                recordingController.elapsedMs,
            ) { state, recArmed, recActive, recElapsed ->
                state.copy(
                    recordingArmed = recArmed,
                    recording = recActive,
                    recordingElapsedMs = recElapsed,
                )
            }.collectLatest { state -> publishState(state) }
        }
        // Wear Listening Stats complication — publish today's estimated
        // listening total + current streak to /stats/today. todayEstimatedMs is
        // finished-chapter based, so it only moves at chapter boundaries:
        // recompute when the current chapter id changes (distinctUntilChanged
        // also fires once up front so the complication has data before the
        // first playback of the session). publishStatsSnapshot dedupes the
        // DataItem write on the (today, streak) value.
        scope.launch {
            controller.state
                .map { it.currentChapterId }
                .distinctUntilChanged()
                .collectLatest { publishStatsSnapshot() }
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

    /** Last published (todayEstimatedMs, currentStreakDays), to dedupe writes. */
    private var lastStats: Pair<Long, Int>? = null

    private suspend fun publishStatsSnapshot() {
        val stats = runCatching { statsRepository.snapshot() }.getOrNull() ?: return
        val pair = stats.todayEstimatedMs to stats.currentStreakDays
        if (pair == lastStats) return
        lastStats = pair
        publishStats(todayMs = pair.first, streakDays = pair.second)
    }

    private suspend fun publishStats(todayMs: Long, streakDays: Int) {
        runCatching {
            val req = PutDataMapRequest.create(PATH_STATS_TODAY).apply {
                dataMap.putLong("todayMs", todayMs)
                dataMap.putInt("streakDays", streakDays)
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
                // Wear companion — Retry after a surfaced playback error. Re-loads
                // the current chapter; loadAndPlay clears the error band, mirroring
                // the phone reader banner's onRetry (playback.play()). No-op if there
                // is no current chapter to reload (e.g. the error nulled the pointer).
                CMD_RETRY -> {
                    val s = controller.state.value
                    val fictionId = s.currentFictionId
                    val chapterId = s.currentChapterId
                    if (fictionId != null && chapterId != null) {
                        controller.play(fictionId, chapterId, s.charOffset)
                    }
                }
                CMD_SLEEP_15 -> controller.startSleepTimer(SleepTimerMode.Duration(15))
                CMD_SLEEP_OFF -> controller.cancelSleepTimer()
                // Sleep timer from the wrist (15/30/45/end-of-chapter). CMD_SLEEP_SET
                // carries a 4-byte SleepPayload (malformed → ignored, same guard as
                // CMD_SEEK); CMD_SLEEP_CANCEL is payload-less.
                CMD_SLEEP_SET -> SleepPayload.decode(event.data)?.let { controller.startSleepTimer(it) }
                CMD_SLEEP_CANCEL -> controller.cancelSleepTimer()
                // Issue #1031 — the circular scrubber sends a target position
                // (ms) in the message payload. A malformed/absent payload
                // decodes to null and is ignored (no blind seek-to-zero).
                CMD_SEEK -> SeekPayload.decode(event.data)?.let { controller.seekToPositionMs(it) }
                // Issue #1308 — teleprompter remote. Toggle/play-pause flip the
                // controller's current value; WPM carries an absolute payload
                // (malformed → ignored, same guard as CMD_SEEK).
                CMD_TELEPROMPTER_TOGGLE ->
                    teleprompterController.setEnabled(!teleprompterController.enabled.value)
                CMD_TELEPROMPTER_PLAY_PAUSE ->
                    teleprompterController.setPlaying(!teleprompterController.playing.value)
                CMD_TELEPROMPTER_WPM ->
                    TeleprompterWpmPayload.decode(event.data)?.let { teleprompterController.setWpm(it) }
                // Issue #1367 (Wear PR1) — recording remote. Payload-less: the
                // controller turns Start into the phone's 3-2-1 countdown +
                // record (only effective when a RecordingScreen is collecting,
                // i.e. recordingArmed); Stop finalizes the clip.
                CMD_RECORDING_START -> recordingController.requestStart()
                CMD_RECORDING_STOP -> recordingController.requestStop()
            }
        }
    }

    companion object {
        const val PATH_STATE = "/playback/state"

        /** DataItem path for the Wear Listening Stats complication —
         *  today's estimated listening (ms) + current streak (days). */
        const val PATH_STATS_TODAY = "/stats/today"
        const val CMD_PLAY = "/playback/cmd/play"
        const val CMD_PAUSE = "/playback/cmd/pause"
        const val CMD_TOGGLE = "/playback/cmd/toggle"
        const val CMD_SKIP_FWD = "/playback/cmd/skipFwd"
        const val CMD_SKIP_BACK = "/playback/cmd/skipBack"
        const val CMD_NEXT_CH = "/playback/cmd/nextCh"
        const val CMD_PREV_CH = "/playback/cmd/prevCh"

        /** Wear companion — re-load the current chapter after a surfaced
         *  playback error (the watch's NowPlaying Retry chip). Payload-less. */
        const val CMD_RETRY = "/playback/cmd/retry"
        const val CMD_SLEEP_15 = "/playback/cmd/sleep15"
        const val CMD_SLEEP_OFF = "/playback/cmd/sleepOff"

        /**
         * Sleep timer from the wrist. [CMD_SLEEP_SET] carries a 4-byte
         * [SleepPayload] (a Duration in minutes, or `0` = end-of-chapter);
         * [CMD_SLEEP_CANCEL] is payload-less. These generalize the older
         * fixed-15-min [CMD_SLEEP_15]/[CMD_SLEEP_OFF] pair to the full
         * 15/30/45/end-of-chapter option set the watch screen offers.
         */
        const val CMD_SLEEP_SET = "/playback/cmd/sleepSet"
        const val CMD_SLEEP_CANCEL = "/playback/cmd/sleepCancel"

        /**
         * Issue #1031 — scrub from the wrist. Unlike the payload-less
         * transport commands above, this message carries an 8-byte
         * [SeekPayload] (absolute target position in ms).
         */
        const val CMD_SEEK = "/playback/cmd/seek"

        /**
         * Issue #1308 — teleprompter remote (wrist) commands. Toggle and
         * play/pause are payload-less (the watch knows current state from the
         * synced teleprompter state); [CMD_TELEPROMPTER_WPM] carries a 4-byte
         * [TeleprompterWpmPayload] (absolute WPM).
         *
         * The `onMessageReceived` dispatch routing these to PR-1's
         * `TeleprompterController` (`setEnabled` / `setPlaying` / `setWpm`)
         * lands at integration once #1320 merges; these constants ship now so
         * the watch send-side ([in.jphe.storyvox.wear.playback.WearPlaybackBridge])
         * speaks the final protocol.
         */
        const val CMD_TELEPROMPTER_TOGGLE = "/playback/cmd/teleprompterToggle"
        const val CMD_TELEPROMPTER_PLAY_PAUSE = "/playback/cmd/teleprompterPlayPause"
        const val CMD_TELEPROMPTER_WPM = "/playback/cmd/teleprompterWpm"

        /**
         * Issue #1367 (Wear PR1) — recording remote (wrist) commands. Both are
         * payload-less: the watch knows recording / armed state from the synced
         * [PlaybackState] (`recordingArmed` / `recording` / `recordingElapsedMs`),
         * and should gate its record button on `recordingArmed` — Start reaches
         * no collector and is dropped if the phone isn't on RecordingScreen.
         *
         * The watch-side UI that sends these is built separately on top of this
         * contract; the constants + dispatch ship here so the phone seam is
         * complete.
         */
        const val CMD_RECORDING_START = "/playback/cmd/recordingStart"
        const val CMD_RECORDING_STOP = "/playback/cmd/recordingStop"
    }
}
