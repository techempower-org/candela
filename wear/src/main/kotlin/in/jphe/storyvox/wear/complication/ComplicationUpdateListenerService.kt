package `in`.jphe.storyvox.wear.complication

import android.content.ComponentName
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge

/**
 * Bridges phone DataItem changes to complication refreshes. The complication
 * services declare `UPDATE_PERIOD_SECONDS=0` (no polling — battery), so this
 * GMS-managed service is what makes Now Playing / "37m today" update promptly:
 * on a `/playback/state` or `/stats/today` change it asks the matching data
 * source to re-emit. Coalesces per buffer so a burst of edits triggers at most
 * one update per complication.
 */
class ComplicationUpdateListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        var refreshNowPlaying = false
        var refreshStats = false
        for (event in events) {
            when (event.dataItem.uri.path) {
                PhoneWearBridge.PATH_STATE -> refreshNowPlaying = true
                PhoneWearBridge.PATH_STATS_TODAY -> refreshStats = true
            }
        }
        if (refreshNowPlaying) requestUpdate(NowPlayingComplicationService::class.java)
        if (refreshStats) requestUpdate(ListeningStatsComplicationService::class.java)
    }

    private fun requestUpdate(service: Class<*>) {
        ComplicationDataSourceUpdateRequester
            .create(this, ComponentName(this, service))
            .requestUpdateAll()
    }
}
