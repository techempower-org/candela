package `in`.jphe.storyvox.deadline

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.feature.techempower.deadline.DeadlineRemindersEnabledSource
import kotlinx.coroutines.runBlocking

/**
 * Issue #1631 — the Hilt-less read of the master `deadlineRemindersEnabled`
 * pref for the two deadline [android.content.BroadcastReceiver]s
 * ([DeadlineBootReceiver], [DeadlineReminderReceiver]), which run without a
 * Hilt-injected constructor. Mirrors the `WidgetEntryPoint` accessor in
 * `NowPlayingWidgetProvider`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DeadlineReminderGateEntryPoint {
    fun remindersEnabled(): DeadlineRemindersEnabledSource
}

/**
 * Reads the master enable pref, **defaulting to `true` on ANY failure** —
 * Hilt graph not ready, DataStore mid-load, or a bind error. This is the
 * load-bearing safety property nebula flagged: a boot-time read that fell
 * through to "disabled" would silently drop EVERY existing user's reminders
 * on the first boot after upgrade. [DeadlineRemindersEnabledSource.enabled]
 * *awaits* the DataStore's first real emission, so a slow load blocks here
 * (fine — callers are already off the main thread) rather than reading a
 * wrong default.
 *
 * **Threading:** blocks via [runBlocking] — call ONLY from a background
 * worker (both receivers wrap this in `goAsync()` + a worker thread), never
 * from `onReceive`'s main thread.
 */
internal fun deadlineRemindersEnabledOrTrue(context: Context): Boolean = runCatching {
    EntryPoints.get(context.applicationContext, DeadlineReminderGateEntryPoint::class.java)
        .remindersEnabled()
        .let { source -> runBlocking { source.enabled() } }
}.getOrDefault(true)
