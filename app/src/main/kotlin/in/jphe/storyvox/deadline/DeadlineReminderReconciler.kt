package `in`.jphe.storyvox.deadline

import `in`.jphe.storyvox.feature.api.SettingsRepositoryUi
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderScheduler
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Issue #1631 — keeps the on-device deadline alarms in sync with the master
 * `deadlineRemindersEnabled` pref while the app process is alive. Owned by
 * [`in`.jphe.storyvox.StoryvoxApp], started from the deferred-init block
 * (same posture as `WidgetStateObserver`).
 *
 * Reacts ONLY to *flips* of the pref (`drop(1)` skips the replay-on-subscribe
 * value) because the other alarm owners already cover the rest:
 *  - new reminders are armed at creation (`DeadlineKeeperViewModel`, gated),
 *  - reboots are re-armed by [DeadlineBootReceiver],
 *  - AlarmManager alarms survive process death, so a normal relaunch needs
 *    no re-arm — only an in-session toggle does.
 *
 * On a flip ([reconcile]):
 *  - **ON**  → re-arm every reminder in the store (`rescheduleAll`).
 *  - **OFF** → cancel every armed alarm.
 *
 * The store is NEVER mutated, so turning off *silences* reminders without
 * deleting the user's deadlines; turning back on restores exactly the same
 * set (and boot re-arms them too, while enabled).
 */
@Singleton
class DeadlineReminderReconciler @Inject constructor(
    private val settings: SettingsRepositoryUi,
    private val scheduler: DeadlineReminderScheduler,
    private val store: DeadlineReminderStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Idempotent — calling [start] twice is a no-op. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            settings.settings
                .map { it.deadlineRemindersEnabled }
                .distinctUntilChanged()
                .drop(1) // skip the current value; react only to in-session flips
                .collect { enabled -> reconcile(enabled) }
        }
    }

    /**
     * Cancel-all (OFF) or re-arm-all-from-store (ON). Extracted for unit
     * tests; never deletes from the store.
     */
    internal suspend fun reconcile(enabled: Boolean) {
        val reminders = store.all()
        if (enabled) {
            scheduler.rescheduleAll(reminders)
        } else {
            reminders.forEach { scheduler.cancel(it) }
        }
    }

    internal fun stop() {
        job?.cancel()
        job = null
    }
}
