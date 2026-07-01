package `in`.jphe.storyvox.data.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.R
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #907 — fires an Android system notification when [NewChapterPollWorker]
 * detects new chapters on a fiction the user follows / subscribes to.
 *
 * Lives in `:core-data` next to the worker that calls it. To deep-link the
 * tap into the reader without depending on `:app`, the content intent targets
 * [MAIN_ACTIVITY] by fully-qualified name and carries the same
 * `storyvox.open_reader.*` extras that
 * [`in`.jphe.storyvox.navigation.DeepLinkResolver] already decodes for the
 * playback notification — so the existing nav plumbing routes the tap to the
 * first new chapter.
 *
 * Issue #1455 — unlike the playback notification and now-playing widget
 * (which point at the chapter already loaded in the PlaybackController), this
 * deep-links a brand-new chapter the controller has never loaded. The reader
 * is a passive view of the controller, so navigating alone would leave it
 * stuck on "loading chapter" (until the 30s timeout). We therefore also set
 * [EXTRA_OPEN_READER_PRELOAD]; MainActivity keys on it to `startListening`
 * the chapter before navigating, mirroring the inbox (#1343) and history
 * (#1350) navigate-without-load fixes. The playback notification / widget
 * omit the flag, so their tap stays navigate-only (a preload there would
 * pause the audio the user is actively listening to).
 *
 * One notification per fiction (notification id derived from the fiction id),
 * so a poll that finds chapters across several followed fictions surfaces one
 * line each rather than overwriting a single entry. They share a group key so
 * Android collapses them under a summary on the lock screen.
 */
@Singleton
class NewChapterNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_NEW_CHAPTERS) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_NEW_CHAPTERS,
                        "New Chapters",
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ).apply {
                        description = "New chapters on fictions you follow"
                        setShowBadge(true)
                    },
                )
            }
        }
    }

    /**
     * Post (or update in place) the new-chapter notification for one fiction.
     *
     * @param fictionId stable fiction id — also the notification's identity, so
     *   a later poll for the same fiction replaces the prior entry rather than
     *   stacking. The same id seeds the deep-link extras.
     * @param firstNewChapterId chapter the tap should open (the earliest unread
     *   new chapter, matching the Inbox row's deep-link target).
     * @param fictionTitle shown as the notification title.
     * @param newCount number of newly-detected chapters; drives the "N new
     *   chapters" content line.
     */
    fun notifyNewChapters(
        fictionId: String,
        firstNewChapterId: String,
        fictionTitle: String,
        newCount: Int,
    ) {
        ensureChannel()

        val nm = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS (API 33+) may be denied — notify() then no-ops at
        // the framework, but areNotificationsEnabled() lets us skip the build
        // entirely and keeps lint quiet about the missing permission check.
        if (!nm.areNotificationsEnabled()) return

        val plural = if (newCount == 1) "chapter" else "chapters"
        val notif = NotificationCompat.Builder(context, CHANNEL_NEW_CHAPTERS)
            .setSmallIcon(R.drawable.ic_new_chapter_notif)
            .setContentTitle(fictionTitle)
            .setContentText("$newCount new $plural")
            .setContentIntent(buildReaderIntent(fictionId, firstNewChapterId))
            .setAutoCancel(true)
            .setGroup(GROUP_NEW_CHAPTERS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { nm.notify(fictionId.hashCode(), notif) }
    }

    private fun buildReaderIntent(fictionId: String, chapterId: String): PendingIntent {
        val launchIntent = Intent().apply {
            component = ComponentName(context.packageName, MAIN_ACTIVITY)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_READER_FICTION_ID, fictionId)
            putExtra(EXTRA_OPEN_READER_CHAPTER_ID, chapterId)
            // Issue #1455 — load this (brand-new, never-played) chapter into
            // the PlaybackController before navigating; without it the passive
            // reader hangs on "loading chapter". The playback notification and
            // now-playing widget omit this flag (their chapter is already the
            // one playing).
            putExtra(EXTRA_OPEN_READER_PRELOAD, true)
        }
        // FLAG_UPDATE_CURRENT so the per-fiction extras overwrite a prior
        // intent for the same request code (PendingIntent equality ignores
        // extras); per-fiction request code keeps distinct fictions' taps
        // from clobbering each other.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(context, fictionId.hashCode(), launchIntent, flags)
    }

    companion object {
        const val CHANNEL_NEW_CHAPTERS = "new_chapters"
        private const val GROUP_NEW_CHAPTERS = "in.jphe.storyvox.NEW_CHAPTERS"
        private const val MAIN_ACTIVITY = "in.jphe.storyvox.MainActivity"
        // Mirror DeepLinkResolver.EXTRA_OPEN_READER_* — kept in sync by string.
        private const val EXTRA_OPEN_READER_FICTION_ID = "storyvox.open_reader.fiction_id"
        private const val EXTRA_OPEN_READER_CHAPTER_ID = "storyvox.open_reader.chapter_id"
        private const val EXTRA_OPEN_READER_PRELOAD = "storyvox.open_reader.preload"
    }
}
