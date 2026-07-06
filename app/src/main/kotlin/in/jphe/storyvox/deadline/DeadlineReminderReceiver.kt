package `in`.jphe.storyvox.deadline

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import `in`.jphe.storyvox.R
import `in`.jphe.storyvox.playback.R as PlaybackR

/**
 * Issue #1515 — fires when a deadline alarm goes off and posts the local
 * reminder notification. Not exported: only our own exact-alarm
 * PendingIntent (explicit component) triggers it.
 *
 * The notification copy travels in the intent extras (set when the alarm
 * was armed), so this receiver needs no storage access. The banner shows
 * the user's chosen text — by default a program label + "deadline", never
 * dollar amounts or case numbers (issue's Privacy note).
 *
 * Issue #1631 — suppressed when the master `deadlineRemindersEnabled` pref
 * is off (read race-safe via [deadlineRemindersEnabledOrTrue], the only
 * Hilt touch). Toggling off cancels pending alarms up front, so this is a
 * belt-and-suspenders guard for an alarm already in flight; either way the
 * reminder in the store is never deleted. The post is moved onto a
 * `goAsync()` worker so the awaited pref read never blocks the main thread.
 */
class DeadlineReminderReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // guarded by [notificationsAllowed] below
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeadlineAlarms.ACTION_FIRE) return

        val title = intent.getStringExtra(DeadlineAlarms.EXTRA_TITLE)
            ?: context.getString(R.string.deadline_notif_title_fallback)
        val body = intent.getStringExtra(DeadlineAlarms.EXTRA_BODY).orEmpty()
        val notifId = intent.getIntExtra(DeadlineAlarms.EXTRA_NOTIF_ID, title.hashCode())

        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Issue #1631 — suppress if deadline reminders are off.
                // Race-safe (default TRUE) and on a worker thread so the
                // awaited pref read never blocks the main thread. The
                // reminder in the store is untouched regardless — an alarm
                // that slips past a just-flipped toggle drops at most one
                // banner, never a saved deadline.
                if (deadlineRemindersEnabledOrTrue(appContext) && notificationsAllowed(appContext)) {
                    DeadlineAlarms.ensureChannel(appContext)

                    val contentIntent = appContext.packageManager
                        .getLaunchIntentForPackage(appContext.packageName)
                        ?.let { launch ->
                            PendingIntent.getActivity(
                                appContext,
                                notifId,
                                launch,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                            )
                        }

                    val notification = NotificationCompat.Builder(appContext, DeadlineAlarms.CHANNEL_ID)
                        .setSmallIcon(PlaybackR.drawable.ic_storyvox_notif)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_REMINDER)
                        .setAutoCancel(true)
                        .apply { contentIntent?.let { setContentIntent(it) } }
                        .build()

                    NotificationManagerCompat.from(appContext).notify(notifId, notification)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
