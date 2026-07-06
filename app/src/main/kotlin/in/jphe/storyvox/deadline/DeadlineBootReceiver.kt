package `in`.jphe.storyvox.deadline

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Issue #1515 — re-arms all deadline reminders after a device reboot.
 *
 * Android drops scheduled alarms on restart, so a "deadline keeper" that
 * silently lost its reminders would be worse than useless. On
 * BOOT_COMPLETED we read the persisted reminders straight off disk (no
 * Hilt needed) and re-schedule each one via [DeadlineAlarms]. All local,
 * no network.
 *
 * Issue #1631 — gated on the master `deadlineRemindersEnabled` pref, read
 * **race-safe** ([deadlineRemindersEnabledOrTrue] defaults TRUE on any
 * failure). This is the regression hotspot: a boot read that fell through
 * to "disabled" would silently drop every existing user's reminders. The
 * store is only ever read here — reminders are never deleted, so a disabled
 * user who re-enables gets them all back on the next boot / flip.
 */
class DeadlineBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                // Off the main thread already (goAsync worker), so the
                // awaited pref read is safe. Default-TRUE on any failure.
                if (deadlineRemindersEnabledOrTrue(appContext)) {
                    DeadlineReminderJson.readAll(appContext).forEach {
                        DeadlineAlarms.schedule(appContext, it)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
