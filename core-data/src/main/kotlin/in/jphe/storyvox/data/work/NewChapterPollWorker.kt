package `in`.jphe.storyvox.data.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.repository.ChapterRepository
import `in`.jphe.storyvox.data.repository.FictionRepository
import `in`.jphe.storyvox.data.repository.InboxNotificationGate
import `in`.jphe.storyvox.data.repository.InboxRepository
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.flow.first

/**
 * Periodic worker that polls each subscribed fiction for new chapters.
 *
 * Walks every `Fiction WHERE inLibrary = 1 AND downloadMode IN (SUBSCRIBE, EAGER)`,
 * fetches the detail page, diffs against cached chapters, inserts new rows
 * (state = NOT_DOWNLOADED), then auto-enqueues `ChapterDownloadWorker` for new
 * chapters when the fiction is in EAGER mode.
 *
 * Cheap-poll path: before the heavier `refreshDetail` call, ask the source
 * for a revision token (e.g. head commit SHA on GitHub). When the source
 * returns the same token we stored on the previous successful poll, skip
 * the full fetch entirely. Sources without a cheap revision check return
 * null (the default impl) and the worker falls back to the full path.
 *
 * Notification rendering (collapsible group, deep links) is the app module's
 * responsibility — this worker only sets the data and returns the count.
 */
@HiltWorker
class NewChapterPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
    private val fictionRepository: FictionRepository,
    private val chapterRepository: ChapterRepository,
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
    /** Issue #383 — write a row to the cross-source Inbox feed when
     *  this poll detects new chapters for a fiction. */
    private val inboxRepository: InboxRepository,
    /** Issue #383 — gates the inbox write per-source. A false here
     *  is the user's "don't notify me about this backend" preference. */
    private val inboxGate: InboxNotificationGate,
    /** Issue #907 — fires an Android system notification when new
     *  chapters land on a followed fiction. Gated by the same per-source
     *  [inboxGate] toggle as the Inbox feed write. */
    private val newChapterNotifier: NewChapterNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        var newChapters = 0
        var skippedByRevisionCheck = 0

        // Issue #907 — poll every fiction the user follows on the source
        // (followedRemotely) as well as the in-library subscribe/eager set.
        // A pure source-side follow that isn't in the local library had no
        // poll path before; this is the query that gives Royal Road follows
        // their new-chapter notifications.
        val rows = fictionDao.pollableForNewChapters()

        for (fiction in rows) {
            // EAGER auto-download keys off the explicit library mode below.
            // A followed-but-not-in-library fiction has no download mode; it
            // still gets detection + inbox + notification, just no auto-queue.
            val mode = fiction.downloadMode

            // Cheap-poll: ask the source for a revision token. If we have
            // a stored token AND it matches, the upstream hasn't changed
            // since last poll — skip the full detail fetch.
            val source = sources[fiction.sourceId]
            if (source != null && fiction.lastSeenRevision != null) {
                val tokenResult = runCatching { source.latestRevisionToken(fiction.id) }
                    .getOrElse { e ->
                        Log.w(TAG, "latestRevisionToken threw for ${fiction.id}", e)
                        null
                    }
                if (tokenResult is FictionResult.Success && tokenResult.value != null &&
                    tokenResult.value == fiction.lastSeenRevision
                ) {
                    skippedByRevisionCheck++
                    continue
                }
                // Otherwise fall through: token mismatch, source returned
                // null (no cheap check available), or the call failed.
                // The full refreshDetail path is the safe default.
            }

            when (val result = fictionRepository.refreshDetail(fiction.id)) {
                is FictionResult.Success -> {
                    // After a successful refresh, persist whatever new
                    // revision token the source has now. We re-ask
                    // because `refreshDetail` doesn't return the token
                    // through its Unit-shaped result; an extra call is
                    // cheap (1-2 GitHub calls) and means subsequent
                    // polls can short-circuit.
                    if (source != null) {
                        runCatching { source.latestRevisionToken(fiction.id) }
                            .onSuccess { r ->
                                if (r is FictionResult.Success && r.value != null) {
                                    fictionDao.setLastSeenRevision(fiction.id, r.value)
                                }
                            }
                            .onFailure { e ->
                                Log.w(TAG, "latestRevisionToken (post-refresh) threw for ${fiction.id}", e)
                            }
                    }

                    val missing = chapterDao.missingForFiction(fiction.id)
                    if (missing.isEmpty()) continue
                    newChapters += missing.size

                    // Issue #383 — emit a cross-source Inbox event for
                    // the user. Gate via the per-source toggle so a
                    // user who flipped Royal Road's inbox toggle off
                    // still gets the library updated (and the chapters
                    // queued in EAGER mode) but doesn't see a row in
                    // the Inbox feed. Coalescing across consecutive
                    // polls happens inside InboxRepository.record —
                    // we always pass the current total, and the repo
                    // updates the existing unread row in place so the
                    // feed doesn't flood.
                    if (inboxGate.isEnabled(fiction.sourceId)) {
                        val deepLinkChapterId = missing.first().id
                        val plural = if (missing.size == 1) "chapter" else "chapters"
                        runCatching {
                            inboxRepository.record(
                                sourceId = fiction.sourceId,
                                fictionId = fiction.id,
                                chapterId = deepLinkChapterId,
                                title = "${missing.size} new $plural in ${fiction.title}",
                                body = null,
                                deepLinkUri = "storyvox://reader/${fiction.id}/$deepLinkChapterId",
                            )
                        }.onFailure { e ->
                            // Inbox write is best-effort; a Room
                            // failure shouldn't fail the entire poll
                            // (the chapter rows are already persisted).
                            Log.w(TAG, "inbox record failed for ${fiction.id}", e)
                        }

                        // Issue #907 — fire the Android system notification
                        // alongside the Inbox row. Same gate, same deep-link
                        // target as the feed entry. Best-effort: a notifier
                        // failure (denied POST_NOTIFICATIONS, OEM throttle)
                        // must not fail the poll or skip the persisted rows.
                        runCatching {
                            newChapterNotifier.notifyNewChapters(
                                fictionId = fiction.id,
                                firstNewChapterId = deepLinkChapterId,
                                fictionTitle = fiction.title,
                                newCount = missing.size,
                            )
                        }.onFailure { e ->
                            Log.w(TAG, "new-chapter notify failed for ${fiction.id}", e)
                        }
                    }

                    if (mode == DownloadMode.EAGER) {
                        for (m in missing) {
                            chapterRepository.queueChapterDownload(
                                fictionId = fiction.id,
                                chapterId = m.id,
                                requireUnmetered = true,
                            )
                        }
                    } else {
                        // SUBSCRIBE mode (or a source-only follow with no
                        // download mode): leave them NOT_DOWNLOADED. The user
                        // sees the row in their library / follows feed and the
                        // #907 notification, and can tap to play on demand.
                        for (m in missing) {
                            chapterDao.setDownloadState(
                                id = m.id,
                                state = ChapterDownloadState.NOT_DOWNLOADED,
                                now = System.currentTimeMillis(),
                                error = null,
                            )
                        }
                    }
                }
                is FictionResult.Failure -> {
                    // Don't fail the whole poll because one fiction is rate-limited.
                    // Continue to the next.
                }
            }
        }

        return Result.success(
            Data.Builder()
                .putInt(KEY_NEW_CHAPTERS, newChapters)
                .putInt(KEY_SKIPPED_BY_REVISION, skippedByRevisionCheck)
                .build(),
        )
    }

    companion object {
        const val TAG = "poll:new-chapters"
        const val UNIQUE_NAME = "poll:new-chapters"
        const val KEY_NEW_CHAPTERS = "newChapterCount"

        /**
         * Telemetry-friendly count of fictions whose poll was skipped
         * because the source's revision token matched the stored one.
         * Surfaced in the Result data so future observability can graph
         * "% of polls that hit the cheap path".
         */
        const val KEY_SKIPPED_BY_REVISION = "skippedByRevision"
    }
}
