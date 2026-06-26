package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Auto-arm sleep timer when the phone enters Bedtime / Sleep mode.
 *
 * Samsung's "Modes and Routines" Sleep schedule, Android's Digital
 * Wellbeing Bedtime mode, and manual DND all surface as a DND
 * interruption-filter change. The playback service listens for
 * [android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED]
 * and, based on the configured trigger mode, may arm the sleep timer
 * at the user's shake-extend duration.
 *
 * Per-device (NOT synced) — a bedroom tablet should auto-arm;
 * a commuting phone probably shouldn't.
 *
 * Issue #1118 — Modes API enhancements:
 * - Support multiple trigger modes (Any DND, Bedtime-only, Time window)
 * - Time-based guards to avoid false positives from daytime DND
 * - User-controlled settings for when auto-sleep should activate
 */
interface BedtimeSleepConfig {

    /** Trigger mode for auto-sleep activation. */
    val bedtimeSleepTriggerMode: Flow<BedtimeSleepTriggerMode>

    /** Start hour for time-window mode (0-23, only used when
     *  trigger mode is TIME_WINDOW). */
    val bedtimeSleepWindowStartHour: Flow<Int>

    /** End hour for time-window mode (0-23, only used when trigger
     *  mode is TIME_WINDOW). */
    val bedtimeSleepWindowEndHour: Flow<Int>

    /** Snapshot of trigger mode for callers that need a single value. */
    suspend fun getBedtimeSleepTriggerMode(): BedtimeSleepTriggerMode

    /** Snapshot of time window start for callers that need a single value. */
    suspend fun getBedtimeSleepWindowStartHour(): Int

    /** Snapshot of time window end for callers that need a single value. */
    suspend fun getBedtimeSleepWindowEndHour(): Int

    // --- Legacy API for backward compatibility ---

    /** Live flow of the toggle state (deprecated; use bedtimeSleepTriggerMode instead). */
    val bedtimeAutoSleepEnabled: Flow<Boolean>

    /** Snapshot for callers that need a single value (deprecated; use getBedtimeSleepTriggerMode instead). */
    suspend fun isBedtimeAutoSleepEnabled(): Boolean
}

/**
 * Determines when the auto-sleep timer should activate on DND.
 */
enum class BedtimeSleepTriggerMode {
    /** Never auto-arm sleep timer on DND. */
    DISABLED,

    /** Auto-arm on ANY DND activation (manual DND, Bedtime mode, etc).
     *  Risk: daytime work DND also triggers. Use TIME_WINDOW for safety. */
    ANY_DND,

    /** Auto-arm only within the configured time window.
     *  E.g., 9 PM - 8 AM: auto-sleep only if DND activates in that range. */
    TIME_WINDOW,

    /** Attempt to detect Bedtime mode specifically (if APIs available).
     *  Falls back to TIME_WINDOW on devices without Bedtime detection. */
    BEDTIME_ONLY,

    /** Attempt to detect Sleep mode specifically (Samsung One UI 5+).
     *  Falls back to TIME_WINDOW on devices without Sleep mode detection. */
    SLEEP_MODE_ONLY;

    companion object {
        /** Legacy mode: true (enabled) maps to ANY_DND; false maps to DISABLED. */
        fun fromLegacy(enabled: Boolean): BedtimeSleepTriggerMode =
            if (enabled) ANY_DND else DISABLED

        /** Legacy mode: DISABLED/TIME_WINDOW→false, anything else→true. */
        fun toLegacy(mode: BedtimeSleepTriggerMode): Boolean =
            mode !in listOf(DISABLED, TIME_WINDOW)
    }
}
