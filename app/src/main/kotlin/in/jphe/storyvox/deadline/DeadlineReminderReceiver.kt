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
 * was armed), so this receiver needs neither Hilt nor storage access. The
 * banner shows the user's chosen text — by default a program label +
 * "deadline", never dollar amounts or case numbers (issue's Privacy note).
 */
class DeadlineReminderReceiver : BroadcastReceiver() {

    @SuppressLint("MissingPermission") // guarded by [notificationsAllowed] below
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DeadlineAlarms.ACTION_FIRE) return

        val title = intent.getStringExtra(DeadlineAlarms.EXTRA_TITLE)
            ?: context.getString(R.string.deadline_notif_title_fallback)
        val body = intent.getStringExtra(DeadlineAlarms.EXTRA_BODY).orEmpty()
        val notifId = intent.getIntExtra(DeadlineAlarms.EXTRA_NOTIF_ID, title.hashCode())

        DeadlineAlarms.ensureChannel(context)

        val contentIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.let { launch ->
                PendingIntent.getActivity(
                    context,
                    notifId,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val notification = NotificationCompat.Builder(context, DeadlineAlarms.CHANNEL_ID)
            .setSmallIcon(PlaybackR.drawable.ic_storyvox_notif)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .apply { contentIntent?.let { setContentIntent(it) } }
            .build()

        if (notificationsAllowed(context)) {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        }
    }

    private fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
