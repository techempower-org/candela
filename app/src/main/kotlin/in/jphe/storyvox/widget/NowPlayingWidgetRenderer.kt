package `in`.jphe.storyvox.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.LayoutRes
import `in`.jphe.storyvox.R
import `in`.jphe.storyvox.data.intent.ReaderIntentContract

/**
 * Issue #159 — pure-functional RemoteViews builder. Given the current
 * snapshot + the host's reported cell dimensions, picks one of three
 * layouts and wires up text / image / PendingIntent fields.
 *
 * Layout buckets:
 *  - 1x1: height < ~110dp AND width < ~180dp → cover only.
 *  - 4x1: height < ~110dp                     → cover + title + 2 transport.
 *  - 4x2: default                             → full layout.
 *
 * (The size thresholds are AppWidgetOptions' min-cell reports in dp.
 * The system also supplies `targetCellWidth` / `targetCellHeight` on
 * API 31+ which we prefer when present — that's the user's actual
 * grid dimension, not the launcher's announce-on-add fallback.)
 */
internal object NowPlayingWidgetRenderer {

    fun build(
        context: Context,
        appWidgetId: Int,
        appWidgetManager: AppWidgetManager,
        snapshot: NowPlayingSnapshot,
    ): RemoteViews {
        // AppWidgetManager.getAppWidgetOptions can return null on rare
        // legacy launcher implementations — guard with an empty bundle.
        val opts: Bundle = appWidgetManager.getAppWidgetOptions(appWidgetId) ?: Bundle()
        val cellWidth = readCellCount(
            opts,
            preferKey = AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,
            api31Key = "appWidgetSizeRequestedCellWidth",
            fallbackCells = 4,
        )
        val cellHeight = readCellCount(
            opts,
            preferKey = AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,
            api31Key = "appWidgetSizeRequestedCellHeight",
            fallbackCells = 2,
        )

        @LayoutRes val layoutId = pickLayout(cellWidth, cellHeight)
        val views = RemoteViews(context.packageName, layoutId)
        bind(context, views, layoutId, snapshot)
        return views
    }

    /**
     * Pick the layout bucket from reported cell counts. The thresholds
     * are deliberately generous on the small side so a user who shrinks
     * to "almost 1x1" doesn't get a layout with overflowing text.
     */
    @LayoutRes
    private fun pickLayout(cellWidth: Int, cellHeight: Int): Int = when {
        cellWidth <= 1 && cellHeight <= 1 -> R.layout.widget_now_playing_1x1
        cellHeight <= 1 -> R.layout.widget_now_playing_4x1
        else -> R.layout.widget_now_playing_4x2
    }

    private fun bind(
        context: Context,
        views: RemoteViews,
        @LayoutRes layoutId: Int,
        snapshot: NowPlayingSnapshot,
    ) {
        // Cover — common to every layout.
        bindCover(views, snapshot)

        // Title block — text on the 4x1 and 4x2 variants only.
        if (layoutId != R.layout.widget_now_playing_1x1) {
            bindTextBlock(context, views, snapshot)
        }

        // Transport buttons — Play/Pause + Next on 4x1, plus Sleep on 4x2.
        if (layoutId == R.layout.widget_now_playing_4x1 ||
            layoutId == R.layout.widget_now_playing_4x2
        ) {
            bindTransport(context, views, snapshot, includeSleep = layoutId == R.layout.widget_now_playing_4x2)
        }

        // Cover / text tap → reader deep-link. Wired on every layout
        // so even the 1x1 cover-only variant is interactive.
        val openReader = PendingIntent.getActivity(
            context,
            REQ_OPEN_READER,
            buildOpenReaderIntent(context, snapshot),
            NowPlayingWidgetProvider.PENDING_FLAGS,
        )
        views.setOnClickPendingIntent(R.id.widget_root, openReader)
    }

    private fun bindCover(views: RemoteViews, snapshot: NowPlayingSnapshot) {
        val coverUri = snapshot.coverUri
        if (coverUri != null && isLocalUri(coverUri)) {
            // RemoteViews.setImageViewUri works for content://, file://,
            // and absolute file paths. Remote http(s) URIs need a
            // bitmap-fetched setImageViewBitmap (Coil-side load is a
            // follow-up; widget is not the right surface to do
            // synchronous network work).
            views.setImageViewUri(R.id.widget_cover, Uri.parse(coverUri))
        } else {
            views.setImageViewResource(R.id.widget_cover, R.drawable.ic_widget_book_placeholder)
        }
    }

    private fun bindTextBlock(
        context: Context,
        views: RemoteViews,
        snapshot: NowPlayingSnapshot,
    ) {
        val bookTitle = snapshot.bookTitle ?: context.getString(R.string.widget_idle_title)
        val chapterTitle = snapshot.chapterTitle ?: context.getString(R.string.widget_idle_body)
        views.setTextViewText(R.id.widget_book_title, bookTitle)
        views.setTextViewText(R.id.widget_chapter_title, chapterTitle)

        // Sleep-timer remaining (4x2 only — element absent from the
        // 4x1 layout, RemoteViews silently no-ops setViewVisibility
        // on absent ids so we always try the show/hide and let the
        // layout dictate whether the view exists).
        val remainingMs = snapshot.sleepTimerRemainingMs
        if (remainingMs != null && remainingMs > 0) {
            val formatted = formatRemaining(remainingMs)
            views.setTextViewText(
                R.id.widget_sleep_remaining,
                context.getString(R.string.widget_sleep_remaining, formatted),
            )
            views.setViewVisibility(R.id.widget_sleep_remaining, View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.widget_sleep_remaining, View.GONE)
        }
    }

    private fun bindTransport(
        context: Context,
        views: RemoteViews,
        snapshot: NowPlayingSnapshot,
        includeSleep: Boolean,
    ) {
        // Play/Pause toggle icon depends on `isPlaying`.
        val playIcon = if (snapshot.isPlaying) {
            R.drawable.ic_widget_pause
        } else {
            R.drawable.ic_widget_play
        }
        views.setImageViewResource(R.id.widget_btn_play_pause, playIcon)
        // Issue #542 — also swap the contentDescription so TalkBack
        // announces the correct verb. Pre-fix, the layout XML hardcoded
        // "Play" so a TalkBack user heard "Play" even while audio was
        // playing; sighted users saw the pause icon but the screen
        // reader and the UI disagreed. RemoteViews has no public
        // setContentDescription, but setCharSequence on the android
        // attribute key works on every API we support (21+); this is
        // the pattern Glance and AOSP MediaSession widgets use.
        val playPauseLabel = if (snapshot.isPlaying) {
            context.getString(R.string.widget_action_pause)
        } else {
            context.getString(R.string.widget_action_play)
        }
        views.setContentDescription(R.id.widget_btn_play_pause, playPauseLabel)
        views.setOnClickPendingIntent(
            R.id.widget_btn_play_pause,
            broadcast(context, NowPlayingWidgetProvider.ACTION_PLAY_PAUSE, REQ_PLAY_PAUSE),
        )

        // Next chapter — disabled visually when idle, but tap still
        // arms the controller (no-op if no chapter loaded; matches
        // bluetooth next-button behavior).
        views.setOnClickPendingIntent(
            R.id.widget_btn_next,
            broadcast(context, NowPlayingWidgetProvider.ACTION_NEXT_CHAPTER, REQ_NEXT),
        )

        if (includeSleep) {
            views.setOnClickPendingIntent(
                R.id.widget_btn_sleep,
                broadcast(context, NowPlayingWidgetProvider.ACTION_SLEEP_TOGGLE, REQ_SLEEP),
            )
        }
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NowPlayingWidgetProvider::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            NowPlayingWidgetProvider.PENDING_FLAGS,
        )
    }

    private fun buildOpenReaderIntent(
        context: Context,
        snapshot: NowPlayingSnapshot,
    ): Intent {
        // Reuse DeepLinkResolver's notification-tap extras so the same
        // navigation path lights up. When idle, omit the extras —
        // MainActivity defaults to Library.
        val intent = Intent().apply {
            component = ComponentName(context, MAIN_ACTIVITY_FQN)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (!snapshot.isIdle) {
            intent.putExtra(ReaderIntentContract.EXTRA_FICTION_ID, snapshot.fictionId)
            intent.putExtra(ReaderIntentContract.EXTRA_CHAPTER_ID, snapshot.chapterId)
        }
        return intent
    }

    /**
     * AppWidgetOptions returns sizes in dp. Cell math is approximate
     * (different launchers use different cell sizes; canonical Pixel
     * is ~74dp per cell on a 5-column grid). We treat ≤110dp height
     * as "1 row" and ≤180dp width as "≤2 columns", which lines up
     * with both the Pixel launcher and Samsung One UI grids.
     *
     * On API 31+ the system also exposes `appWidgetSizeRequested*`
     * keys carrying the user's grid coordinates directly — when
     * present we use them, when absent we fall back to the dp math.
     */
    private fun readCellCount(
        opts: Bundle,
        preferKey: String,
        api31Key: String,
        fallbackCells: Int,
    ): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cells = opts.getInt(api31Key, -1)
            if (cells > 0) return cells
        }
        val sizeDp = opts.getInt(preferKey, -1)
        if (sizeDp <= 0) return fallbackCells
        // 74dp per cell on canonical Pixel; round to nearest.
        return ((sizeDp + 36) / 74).coerceAtLeast(1)
    }

    private fun isLocalUri(value: String): Boolean {
        return value.startsWith("content://") ||
            value.startsWith("file://") ||
            value.startsWith("/")
    }

    private fun formatRemaining(ms: Long): String {
        val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return "%d:%02d".format(minutes, seconds)
    }

    private const val MAIN_ACTIVITY_FQN = "in.jphe.storyvox.MainActivity"

    // Distinct request codes per PendingIntent so the system caches
    // them separately. Without unique codes, PendingIntent.getBroadcast
    // returns the same instance for every action and only the last
    // .setExtras() wins.
    private const val REQ_PLAY_PAUSE = 0x10_01
    private const val REQ_NEXT = 0x10_02
    private const val REQ_SLEEP = 0x10_03
    private const val REQ_OPEN_READER = 0x10_04
}

