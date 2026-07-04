package `in`.jphe.storyvox.deadline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminder
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminderScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1515 — DI-bound [DeadlineReminderScheduler] impl over
 * [DeadlineAlarms] (AlarmManager). Keeps the feature-side VM free of any
 * Android alarm types.
 */
@Singleton
class AlarmDeadlineReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeadlineReminderScheduler {

    override fun schedule(reminder: DeadlineReminder) = DeadlineAlarms.schedule(context, reminder)

    override fun cancel(reminder: DeadlineReminder) = DeadlineAlarms.cancel(context, reminder)

    override fun rescheduleAll(reminders: List<DeadlineReminder>) =
        reminders.forEach { DeadlineAlarms.schedule(context, it) }

    override fun canScheduleExact(): Boolean = DeadlineAlarms.canScheduleExact(context)
}
