package `in`.jphe.storyvox.wear.ongoing

import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge

/**
 * Wear companion — background driver for the Ongoing Activity. As a
 * [WearableListenerService] it receives `/playback/state` DataItem changes even
 * when the watch app is closed, decodes them with the same seam the in-app
 * bridge uses ([WearPlaybackBridge.decodeState]), and starts / stops
 * [WearOngoingPlaybackService] so the watch-face media chip tracks whether the
 * phone is actually playing.
 *
 * Decision logic lives in the pure [ongoingActionFor] so it can be unit-tested
 * without GMS, mirroring the [in.jphe.storyvox.wear.playback.NodeSelection] seam.
 */
class PlaybackStateListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        for (e in events) {
            if (e.dataItem.uri.path != PhoneWearBridge.PATH_STATE) continue
            val raw = DataMapItem.fromDataItem(e.dataItem).dataMap.getString("state")
            val state = WearPlaybackBridge.decodeState(raw, PlaybackState()) { t ->
                Log.w(TAG, "ongoing: dropping undecodable PlaybackState payload", t)
            }
            when (val action = ongoingActionFor(state)) {
                is OngoingAction.Show ->
                    // Background data-layer event → must use startForegroundService
                    // so the service can promote within 5 s (it calls
                    // startForeground in ACTION_UPDATE). BAL caveats: see the
                    // WearOngoingPlaybackService VERIFICATION GAP note.
                    ContextCompat.startForegroundService(
                        this,
                        WearOngoingPlaybackService.updateIntent(this, action.title, action.subtitle),
                    )
                OngoingAction.Hide ->
                    // If the chip service is running (foreground) this delivers the
                    // stop; if it isn't, a background startService throws and there
                    // is nothing to stop — either way the chip ends up absent.
                    runCatching { startService(WearOngoingPlaybackService.stopIntent(this)) }
            }
        }
    }

    companion object {
        private const val TAG = "WearPlaybackListener"
    }
}

/**
 * Pure rule: what should the Ongoing Activity chip do for [state]?
 *
 * Show only while the phone is actively playing a loaded chapter. Gating on
 * `isPlaying` (not merely "a chapter is loaded") avoids a stale chip on watch
 * boot — the last `/playback/state` DataItem persists, so a paused/idle phone
 * would otherwise resurrect a chip for a chapter no longer playing. The title
 * mirrors the in-app [in.jphe.storyvox.wear.screens.NowPlayingScreen] fallback
 * chain (chapter → book → app name).
 */
internal fun ongoingActionFor(state: PlaybackState): OngoingAction =
    if (state.isPlaying && state.currentChapterId != null) {
        OngoingAction.Show(
            title = state.chapterTitle ?: state.bookTitle ?: "storyvox",
            subtitle = state.bookTitle?.takeIf { it.isNotBlank() && state.chapterTitle != null },
        )
    } else {
        OngoingAction.Hide
    }

/** Outcome of [ongoingActionFor] — start/update the chip, or tear it down. */
sealed interface OngoingAction {
    data class Show(val title: String, val subtitle: String?) : OngoingAction
    data object Hide : OngoingAction
}
