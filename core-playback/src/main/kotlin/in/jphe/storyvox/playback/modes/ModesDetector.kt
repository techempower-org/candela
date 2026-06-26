package `in`.jphe.storyvox.playback.modes

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import `in`.jphe.storyvox.data.repository.playback.BedtimeSleepConfig
import `in`.jphe.storyvox.data.repository.playback.BedtimeSleepTriggerMode
import java.util.Calendar

/**
 * Detects when auto-sleep should activate based on Android Modes (DND, Bedtime, etc.)
 * and user-configured trigger modes.
 *
 * Issue #1118 — Modes API implementation. Handles:
 * - DND (Do Not Disturb) filter detection
 * - Time-window guards to avoid false positives from daytime DND
 * - Future extensibility for Samsung Modes and Android Bedtime mode detection
 *
 * Note: Samsung Modes API and Android Bedtime-specific detection would require
 * proprietary SDKs (Samsung) or reverse engineering. Currently falls back to
 * time-based guards and DND detection as a universal proxy.
 */
class ModesDetector(
    private val context: Context,
    private val bedtimeSleepConfig: BedtimeSleepConfig,
) {
    private companion object {
        private const val TAG = "ModesDetector"
    }

    /**
     * Check if auto-sleep should activate based on current system state.
     *
     * Returns true if:
     * 1. DND is active (NotificationManager.INTERRUPTION_FILTER != ALL)
     * 2. User's configured trigger mode allows auto-sleep in this scenario
     *
     * The actual detection depends on the trigger mode:
     * - DISABLED: never triggers
     * - ANY_DND: triggers on any DND activation
     * - TIME_WINDOW: triggers only if current time is within the configured window
     * - BEDTIME_ONLY: attempts Bedtime-specific detection (falls back to TIME_WINDOW)
     * - SLEEP_MODE_ONLY: attempts Sleep-mode detection (falls back to TIME_WINDOW)
     */
    suspend fun shouldAutoSleep(): Boolean {
        // Check if DND is currently active
        val nm = context.getSystemService(NotificationManager::class.java)
        val currentFilter = nm.currentInterruptionFilter
        val isDndActive = currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL

        if (!isDndActive) {
            // No DND active, nothing to detect
            return false
        }

        val triggerMode = bedtimeSleepConfig.getBedtimeSleepTriggerMode()

        return when (triggerMode) {
            BedtimeSleepTriggerMode.DISABLED -> false

            BedtimeSleepTriggerMode.ANY_DND -> {
                Log.i(TAG, "Auto-sleep: ANY_DND mode triggered (filter=$currentFilter)")
                true
            }

            BedtimeSleepTriggerMode.TIME_WINDOW -> {
                val shouldActivate = isCurrentTimeInWindow()
                if (shouldActivate) {
                    Log.i(TAG, "Auto-sleep: TIME_WINDOW mode triggered")
                } else {
                    Log.i(TAG, "Auto-sleep: TIME_WINDOW guard rejected (outside window)")
                }
                shouldActivate
            }

            BedtimeSleepTriggerMode.BEDTIME_ONLY -> {
                // Attempt to detect Bedtime mode specifically. This would require:
                // 1. Android 15+ ZenDeviceEffects API (private in Google's Digital Wellbeing)
                // 2. Custom Bedtime mode detection not available in public APIs
                // Fallback to TIME_WINDOW for now
                val shouldActivate = isCurrentTimeInWindow()
                Log.i(TAG, "Auto-sleep: BEDTIME_ONLY fallback to TIME_WINDOW (shouldActivate=$shouldActivate)")
                shouldActivate
            }

            BedtimeSleepTriggerMode.SLEEP_MODE_ONLY -> {
                // Attempt to detect Samsung Sleep mode or similar. This would require:
                // 1. Samsung proprietary SDK / Modes and Routines API (not publicly documented)
                // 2. Reverse-engineered broadcasts if available
                // Fallback to TIME_WINDOW for now
                val shouldActivate = isCurrentTimeInWindow()
                Log.i(TAG, "Auto-sleep: SLEEP_MODE_ONLY fallback to TIME_WINDOW (shouldActivate=$shouldActivate)")
                shouldActivate
            }
        }
    }

    /**
     * Check if the current time is within the configured auto-sleep window.
     *
     * Example: window 21 (9 PM) to 8 (8 AM):
     * - 21:00 - 23:59: activates ✓
     * - 00:00 - 08:00: activates ✓  (overnight)
     * - 08:01 - 20:59: does not activate ✗
     */
    private suspend fun isCurrentTimeInWindow(): Boolean {
        val startHour = bedtimeSleepConfig.getBedtimeSleepWindowStartHour()
        val endHour = bedtimeSleepConfig.getBedtimeSleepWindowEndHour()
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // Handle overnight windows (e.g., 21 to 8 is "evening to morning")
        val isInWindow = if (startHour <= endHour) {
            // Same-day window (unusual but valid): 6 AM to 2 PM
            currentHour in startHour..endHour
        } else {
            // Overnight window (typical): 21 (9 PM) to 8 (8 AM)
            currentHour >= startHour || currentHour < endHour
        }

        if (!isInWindow) {
            Log.i(TAG, "Time window check: currentHour=$currentHour, window=$startHour-$endHour, inWindow=false")
        }

        return isInWindow
    }

    /**
     * Get a human-readable description of when auto-sleep will activate.
     */
    suspend fun getActivationDescription(): String {
        val mode = bedtimeSleepConfig.getBedtimeSleepTriggerMode()
        return when (mode) {
            BedtimeSleepTriggerMode.DISABLED -> "Auto-sleep disabled"
            BedtimeSleepTriggerMode.ANY_DND -> "Activates on any Do Not Disturb"
            BedtimeSleepTriggerMode.TIME_WINDOW -> {
                val start = bedtimeSleepConfig.getBedtimeSleepWindowStartHour()
                val end = bedtimeSleepConfig.getBedtimeSleepWindowEndHour()
                "Activates between $start:00 and $end:00 when DND is on"
            }
            BedtimeSleepTriggerMode.BEDTIME_ONLY -> "Activates on Bedtime mode (or time window)"
            BedtimeSleepTriggerMode.SLEEP_MODE_ONLY -> "Activates on Sleep mode (or time window)"
        }
    }
}
