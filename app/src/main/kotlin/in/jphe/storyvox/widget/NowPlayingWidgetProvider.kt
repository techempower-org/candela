package `in`.jphe.storyvox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import `in`.jphe.storyvox.playback.PlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Issue #159 — home-screen now-playing widget.
 *
 * Adaptive RemoteViews: three layout buckets selected by cell size:
 *  - 1x1 (≤2x1): cover only.
 *  - 4x1 (height = 1): cover + title + Play/Pause + Next chapter.
 *  - 4x2 (default): cover + title + chapter + sleep timer + full transport.
 *
 * State flow:
 *  1. [WidgetStateObserver] (owned by [`in`.jphe.storyvox.StoryvoxApp])
 *     subscribes to [PlaybackController.state] and re-broadcasts a
 *     [ACTION_REFRESH] intent to every widget host whenever a
 *     user-visible field changes (title, chapter, isPlaying, sleep
 *     timer). That broadcast lands in [onReceive] which rebuilds the
 *     RemoteViews from the latest snapshot — no `WorkManager` periodic
 *     because the controller is already the source of truth.
 *  2. Transport buttons dispatch [ACTION_PLAY_PAUSE], [ACTION_NEXT_CHAPTER]
 *     and [ACTION_SLEEP_TOGGLE] back to this provider via PendingIntent.
 *     Each one materializes the singleton [PlaybackController] via a
 *     [WidgetEntryPoint] lookup and invokes the same methods that the
 *     in-app transport row uses — no service-side duplication.
 *  3. Tapping the cover routes through a [PendingIntent.getActivity]
 *     into MainActivity with the same `ReaderIntentContract` extras the
 *     notification carries (see DeepLinkResolver in navigation/), so
 *     the user lands on the audiobook screen for the playing chapter.
 *
 * R8 keep:
 *   `-keep class in.jphe.storyvox.widget.NowPlayingWidgetProvider { *; }`
 *   (proguard-rules.pro) — Android system instantiates AppWidgetProvider
 *   subclasses via reflection from the FQN in AndroidManifest.xml. R8
 *   doesn't see that edge and would strip / rename the class.
 *
 * Threading:
 *   onReceive runs on the main thread (BroadcastReceiver contract). We
 *   `goAsync()` only for the `nextChapter` path because it's a suspend
 *   call; the synchronous transport methods (`togglePlayPause`,
 *   `toggleSleepTimer`) return immediately.
 */
class NowPlayingWidgetProvider : AppWidgetProvider() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface WidgetEntryPoint {
        fun playbackController(): PlaybackController
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Either freshly added, or the system periodic update fired
        // (we set updatePeriodMillis=0 so this only runs on enable /
        // resize / explicit refresh). Re-render every host with the
        // current PlaybackController snapshot.
        val snapshot = readSnapshot(context)
        for (id in appWidgetIds) {
            val views = NowPlayingWidgetRenderer.build(
                context = context,
                appWidgetId = id,
                appWidgetManager = appWidgetManager,
                snapshot = snapshot,
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        // Re-pick the layout bucket whenever the user resizes. The
        // host calls this whenever the cell width/height changes; we
        // don't need to track the previous bucket because
        // NowPlayingWidgetRenderer is pure-functional over the
        // current options bundle.
        val snapshot = readSnapshot(context)
        val views = NowPlayingWidgetRenderer.build(
            context = context,
            appWidgetId = appWidgetId,
            appWidgetManager = appWidgetManager,
            snapshot = snapshot,
        )
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                runSafely(context) { it.togglePlayPause() }
                pushRefresh(context)
            }
            ACTION_NEXT_CHAPTER -> {
                // suspend call — defer to a coroutine. goAsync() keeps
                // the receiver alive until pendingResult.finish() runs
                // on the same scope after the suspend returns.
                val pending = goAsync()
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                scope.launch {
                    try {
                        runSuspendSafely(context) { it.nextChapter() }
                        pushRefresh(context)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_SLEEP_TOGGLE -> {
                runSafely(context) { it.toggleSleepTimer() }
                pushRefresh(context)
            }
            ACTION_REFRESH -> {
                // External broadcast (from WidgetStateObserver or
                // anything else that wants the widget redrawn). Just
                // re-run onUpdate manually for every host id.
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, NowPlayingWidgetProvider::class.java),
                )
                onUpdate(context, mgr, ids)
            }
        }
    }

    /**
     * Issue #159 — broadcast a refresh to ourselves. Cheaper than
     * calling AppWidgetManager.updateAppWidget directly because
     * onReceive(ACTION_REFRESH) handles host-id enumeration in one
     * place. Used by [WidgetStateObserver] every time a user-visible
     * field on [PlaybackController.state] changes.
     */
    private fun pushRefresh(context: Context) {
        val refresh = Intent(context, NowPlayingWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
        }
        context.sendBroadcast(refresh)
    }

    private fun readSnapshot(context: Context): NowPlayingSnapshot {
        return try {
            val controller = EntryPoints
                .get(context.applicationContext, WidgetEntryPoint::class.java)
                .playbackController()
            NowPlayingSnapshot.from(controller.state.value)
        } catch (t: Throwable) {
            // Hilt graph not ready yet (extremely unlikely at this
            // point — Application.onCreate has already run by the
            // time any widget broadcast lands) or another bind
            // failure. Render the idle layout rather than crashing
            // the host process; widget receivers crashing leave
            // ghost layouts on the launcher until the user re-adds
            // the widget.
            Log.w(TAG, "Failed to read playback snapshot for widget: ${t.message}")
            NowPlayingSnapshot.Idle
        }
    }

    private inline fun runSafely(context: Context, block: (PlaybackController) -> Unit) {
        try {
            val controller = EntryPoints
                .get(context.applicationContext, WidgetEntryPoint::class.java)
                .playbackController()
            block(controller)
        } catch (t: Throwable) {
            Log.w(TAG, "Widget transport action failed: ${t.message}")
        }
    }

    private suspend inline fun runSuspendSafely(
        context: Context,
        block: (PlaybackController) -> Unit,
    ) {
        try {
            val controller = EntryPoints
                .get(context.applicationContext, WidgetEntryPoint::class.java)
                .playbackController()
            block(controller)
        } catch (t: Throwable) {
            Log.w(TAG, "Widget async action failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "NowPlayingWidget"

        /** Toggle play/pause on the active chapter. No-op when nothing is loaded. */
        const val ACTION_PLAY_PAUSE = "in.jphe.storyvox.widget.action.PLAY_PAUSE"

        /** Advance to the next chapter. Uses PlaybackController.nextChapter()
         *  which is a suspend call; we wrap with goAsync(). */
        const val ACTION_NEXT_CHAPTER = "in.jphe.storyvox.widget.action.NEXT_CHAPTER"

        /** Start a 15min sleep timer, or cancel the active one — same
         *  PlaybackController.toggleSleepTimer() the bluetooth long-press
         *  fires. */
        const val ACTION_SLEEP_TOGGLE = "in.jphe.storyvox.widget.action.SLEEP_TOGGLE"

        /** Internal — pushed by [WidgetStateObserver] every time a
         *  user-visible PlaybackState field changes. Rebroadcasts to
         *  every active widget id. */
        const val ACTION_REFRESH = "in.jphe.storyvox.widget.action.REFRESH"

        /** Convenience for [WidgetStateObserver] — emits a refresh
         *  broadcast without needing to import the action constant
         *  + build the intent inline. */
        fun broadcastRefresh(context: Context) {
            val intent = Intent(context, NowPlayingWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            context.sendBroadcast(intent)
        }

        /** PendingIntent flags appropriate for widget transport
         *  broadcasts — IMMUTABLE is required on API 31+, UPDATE_CURRENT
         *  so repeat-rendering with new extras (per-widget-id requests)
         *  overwrites the prior intent payload. */
        const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
