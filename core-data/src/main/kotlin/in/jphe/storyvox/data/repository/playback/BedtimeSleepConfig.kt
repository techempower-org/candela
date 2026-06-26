package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Auto-arm sleep timer when the phone enters Bedtime / Sleep mode.
 *
 * Samsung's "Modes and Routines" Sleep schedule, Android's Digital
 * Wellbeing Bedtime mode, and manual DND all surface as a DND
 * interruption-filter change. The playback service listens for
 * [android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED]
 * and, when the filter moves away from ALL (i.e. DND activates) while
 * playback is running, arms the sleep timer at the user's shake-extend
 * duration.
 *
 * Per-device (NOT synced) — a bedroom tablet should auto-arm;
 * a commuting phone probably shouldn't.
 */
interface BedtimeSleepConfig {

    /** Live flow of the toggle state. */
    val bedtimeAutoSleepEnabled: Flow<Boolean>

    /** Snapshot for callers that need a single value. */
    suspend fun isBedtimeAutoSleepEnabled(): Boolean
}
