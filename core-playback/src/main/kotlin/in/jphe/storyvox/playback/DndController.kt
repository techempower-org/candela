package `in`.jphe.storyvox.playback

import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.repository.playback.SleepTimerDndConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1190 — toggles system Do Not Disturb around the sleep timer's
 * lifetime.
 *
 * [SleepTimer] calls [activateForSleepTimer] when a timer is armed and
 * [restorePrevious] when it expires or is cancelled. Pulled behind an
 * interface so the pure-Kotlin [SleepTimer] stays Android-free and unit
 * tests can supply [NoopDndController].
 */
interface DndController {
    /**
     * Enable DND **iff** the user opted in AND notification-policy access
     * is granted AND DND isn't already on. Snapshots the prior
     * interruption filter so [restorePrevious] can put it back.
     * Idempotent — a no-op in every other case (including a second call
     * while already holding DND).
     */
    suspend fun activateForSleepTimer()

    /**
     * Restore the interruption filter captured by the matching
     * [activateForSleepTimer]. No-op if we never activated — in
     * particular, if the user already had DND on when the timer armed we
     * recorded nothing, so we must not switch their DND off here.
     */
    fun restorePrevious()
}

/** Test / no-Android default. */
object NoopDndController : DndController {
    override suspend fun activateForSleepTimer() = Unit
    override fun restorePrevious() = Unit
}

@Singleton
class AndroidDndController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: SleepTimerDndConfig,
) : DndController {

    private val notificationManager: NotificationManager?
        get() = context.getSystemService(NotificationManager::class.java)

    /**
     * The interruption filter in effect before we switched DND on, or
     * `null` when we are not currently holding DND on the timer's behalf.
     * Doubles as the idempotency guard: a non-null value means "already
     * active", so a second [activateForSleepTimer] is a no-op.
     */
    private var savedFilter: Int? = null

    override suspend fun activateForSleepTimer() {
        if (!config.currentDndWithSleepTimerEnabled()) return
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        // Already holding DND for this timer — don't re-snapshot.
        if (savedFilter != null) return
        val current = nm.currentInterruptionFilter
        // Only act when DND is currently OFF. If the user already has a
        // DND mode on, leave it untouched and record nothing, so
        // restorePrevious() can't switch their DND off later (the #1190
        // "already enabled by user" edge case).
        if (current != NotificationManager.INTERRUPTION_FILTER_ALL) return
        savedFilter = current
        // PRIORITY (not NONE) so alarms still fire — a bedtime listener
        // still needs their morning alarm.
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    override fun restorePrevious() {
        val saved = savedFilter ?: return
        savedFilter = null
        val nm = notificationManager ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        nm.setInterruptionFilter(saved)
    }
}
