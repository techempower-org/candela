package `in`.jphe.storyvox.deadline

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import `in`.jphe.storyvox.R
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineReminder
import java.time.LocalDate
import java.time.ZoneId

/**
 * Issue #1515 — the AlarmManager plumbing for local deadline reminders.
 *
 * Stateless, Context-only helpers shared by [AlarmDeadlineReminderScheduler]
 * (the DI-bound seam impl) AND [DeadlineBootReceiver] (which re-arms after
 * a reboot without Hilt). Everything here is on-device: an alarm →
 * broadcast → local notification, no network at any step (airplane-mode
 * safe).
 */
object DeadlineAlarms {

    const val ACTION_FIRE = "in.jphe.storyvox.deadline.action.FIRE"
    const val EXTRA_TITLE = "in.jphe.storyvox.deadline.extra.TITLE"
    const val EXTRA_BODY = "in.jphe.storyvox.deadline.extra.BODY"
    const val EXTRA_NOTIF_ID = "in.jphe.storyvox.deadline.extra.NOTIF_ID"

    const val CHANNEL_ID = "benefits_deadlines"

    /** Reminders fire at 9am local on their target day. */
    private const val FIRE_HOUR = 9

    /** Whether the OS currently allows exact alarms (Android 12+ gate). */
    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    /** Arm an alarm for every still-future fire date of [reminder]. */
    fun schedule(context: Context, reminder: DeadlineReminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val exact = canScheduleExact(context)
        reminder.fireDates().forEach { date ->
            val fireAt = date.atTime(FIRE_HOUR, 0).atZone(zone).toInstant().toEpochMilli()
            if (fireAt <= now) return@forEach // don't schedule a fire in the past
            val pi = firePendingIntent(context, reminder, date)
            if (exact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                // Exact not granted → inexact but still delivered while idle.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
        }
    }

    /** Cancel every pending alarm for [reminder]. */
    fun cancel(context: Context, reminder: DeadlineReminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        reminder.fireDates().forEach { date ->
            am.cancel(firePendingIntent(context, reminder, date))
        }
    }

    /** Create the notification channel if it doesn't exist yet (idempotent). */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.deadline_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.deadline_channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    /** Stable notification id per (reminder, fire date) — also the request code. */
    fun notifId(reminderId: String, date: LocalDate): Int =
        reminderId.hashCode() * 31 + date.toEpochDay().toInt()

    private fun firePendingIntent(
        context: Context,
        reminder: DeadlineReminder,
        date: LocalDate,
    ): PendingIntent {
        val notifId = notifId(reminder.id, date)
        val intent = Intent(context, DeadlineReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_TITLE, reminder.notificationTitle)
            putExtra(EXTRA_BODY, reminder.notificationBody)
            putExtra(EXTRA_NOTIF_ID, notifId)
        }
        return PendingIntent.getBroadcast(
            context,
            notifId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
