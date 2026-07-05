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
 */
class DeadlineBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                DeadlineReminderJson.readAll(appContext).forEach {
                    DeadlineAlarms.schedule(appContext, it)
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}
