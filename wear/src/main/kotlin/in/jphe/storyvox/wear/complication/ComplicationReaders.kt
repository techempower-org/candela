package `in`.jphe.storyvox.wear.complication

import android.content.Context
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import `in`.jphe.storyvox.playback.PlaybackState
import `in`.jphe.storyvox.playback.wear.PhoneWearBridge
import `in`.jphe.storyvox.wear.playback.WearPlaybackBridge
import kotlinx.coroutines.tasks.await

/** Today's listening glance for the Listening Stats complication. */
internal data class TodayStats(val todayMs: Long, val streakDays: Int)

/**
 * Read the latest phone-published [PlaybackState] from the cached
 * `/playback/state` DataItem — the same source [WearPlaybackBridge] consumes for
 * the Now Playing screen. Returns an empty state when GMS is unavailable or no
 * item has synced yet, and reuses the bridge's tested [WearPlaybackBridge.decodeState]
 * so a malformed/version-skewed blob degrades to empty instead of crashing the
 * complication.
 */
internal suspend fun Context.readPlaybackState(): PlaybackState {
    val buffer = runCatching { Wearable.getDataClient(this).dataItems.await() }.getOrNull()
        ?: return PlaybackState()
    return try {
        var state = PlaybackState()
        for (item in buffer) {
            if (item.uri.path == PhoneWearBridge.PATH_STATE) {
                val raw = DataMapItem.fromDataItem(item).dataMap.getString("state")
                state = WearPlaybackBridge.decodeState(raw, state)
            }
        }
        state
    } finally {
        buffer.release()
    }
}

/**
 * Read the latest `/stats/today` DataItem (today's estimated listening ms +
 * current streak), pushed by [PhoneWearBridge]. Returns zeros when nothing has
 * synced yet.
 */
internal suspend fun Context.readTodayStats(): TodayStats {
    val buffer = runCatching { Wearable.getDataClient(this).dataItems.await() }.getOrNull()
        ?: return TodayStats(todayMs = 0L, streakDays = 0)
    return try {
        var stats = TodayStats(todayMs = 0L, streakDays = 0)
        for (item in buffer) {
            if (item.uri.path == PhoneWearBridge.PATH_STATS_TODAY) {
                val map = DataMapItem.fromDataItem(item).dataMap
                stats = TodayStats(
                    todayMs = map.getLong("todayMs"),
                    streakDays = map.getInt("streakDays"),
                )
            }
        }
        stats
    } finally {
        buffer.release()
    }
}
