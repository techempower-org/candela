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
        var skippedByRevisionCheck = 0
        // Sum of genuinely-new chapters across all fictions this poll —
        // the delta since the previous poll, NOT the cumulative
        // NOT_DOWNLOADED backlog. This is the count the user is told
        // about, and what KEY_NEW_CHAPTERS reports.
        var newChapterDelta = 0

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

            // Snapshot the chapter ids we already hold BEFORE the refresh
            // mutates the chapter table. The genuine "new since last poll"
            // delta is the set of chapters that appear after the refresh
            // but were not here before — NOT the full NOT_DOWNLOADED
            // backlog. A followed/SUBSCRIBE fiction never downloads its
            // chapters, so `missingForFiction` (downloadState =
            // NOT_DOWNLOADED) is the entire un-played history; using its
            // size as the "new" count made every poll re-announce "65 new
            // chapters" instead of the one chapter that actually landed.
            val priorChapterIds = chapterDao.chapterIdsForFiction(fiction.id).toSet()

            // #1314 — force past the TTL guard: a scheduled poll must always
            // re-fetch to detect new chapters, even if the detail page was
            // opened (and the row hydrated) within the TTL window.
            when (val result = fictionRepository.refreshDetail(fiction.id, force = true)) {
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

                    // Compute what (if anything) to announce. `missing`
                    // (the whole NOT_DOWNLOADED backlog) still drives EAGER
                    // auto-download below — but the user-facing count,
                    // plural label and deep-link target all come from the
                    // delta computed here. See [newChapterAnnouncement].
                    val announcement = newChapterAnnouncement(
                        missingChapterIds = missing.map { it.id },
                        priorChapterIds = priorChapterIds,
                    )
                    newChapterDelta += announcement.newCount

                    // Issue #383 / #907 — emit the cross-source Inbox event
                    // and the Android system notification. Gate via the
                    // per-source toggle so a user who flipped Royal Road's
                    // inbox toggle off still gets the library updated (and
                    // chapters queued in EAGER mode) but sees no feed row or
                    // notification. Both reflect the delta since the
                    // previous poll, not the cumulative NOT_DOWNLOADED
                    // backlog, and are skipped on the first poll baseline.
                    val deepLinkChapterId = announcement.deepLinkChapterId
                    if (announcement.shouldNotify && deepLinkChapterId != null &&
                        inboxGate.isEnabled(fiction.sourceId)
                    ) {
                        runCatching {
                            inboxRepository.record(
                                sourceId = fiction.sourceId,
                                fictionId = fiction.id,
                                chapterId = deepLinkChapterId,
                                title = "${announcement.newCount} new ${announcement.pluralLabel} in ${fiction.title}",
                                body = null,
                                deepLinkUri = "storyvox://reader/${fiction.id}/$deepLinkChapterId",
                                newChapterCount = announcement.newCount,
                                fictionTitle = fiction.title,
                            )
                        }.onFailure { e ->
                            // Inbox write is best-effort; a Room
                            // failure shouldn't fail the entire poll
                            // (the chapter rows are already persisted).
                            Log.w(TAG, "inbox record failed for ${fiction.id}", e)
                        }

                        // Best-effort: a notifier failure (denied
                        // POST_NOTIFICATIONS, OEM throttle) must not fail the
                        // poll or skip the persisted rows.
                        runCatching {
                            newChapterNotifier.notifyNewChapters(
                                fictionId = fiction.id,
                                firstNewChapterId = deepLinkChapterId,
                                fictionTitle = fiction.title,
                                newCount = announcement.newCount,
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
                .putInt(KEY_NEW_CHAPTERS, newChapterDelta)
                .putInt(KEY_SKIPPED_BY_REVISION, skippedByRevisionCheck)
                .build(),
        )
    }

    companion object {
        const val TAG = "poll:new-chapters"
        const val UNIQUE_NAME = "poll:new-chapters"

        /**
         * Count of genuinely-new chapters detected this poll — the delta
         * across all polled fictions since the previous poll, NOT the
         * cumulative NOT_DOWNLOADED backlog. A followed/SUBSCRIBE fiction
         * never downloads its chapters, so the backlog is the whole
         * unplayed history; reporting its size made the notification say
         * "65 new chapters" when one chapter had actually landed.
         */
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

/**
 * What [NewChapterPollWorker] should tell the user about one fiction after a
 * poll: how many chapters are genuinely new since the previous poll, the
 * pluralised label, and which chapter a tap should open.
 *
 * @param newCount chapters new since the previous poll (the delta).
 * @param pluralLabel "chapter" when [newCount] is 1, else "chapters".
 * @param deepLinkChapterId the earliest new chapter to deep-link into, or
 *   null when there is nothing to announce.
 * @param shouldNotify true only when there is a real delta worth surfacing —
 *   false on the first-poll baseline and when no chapter is actually new.
 */
data class NewChapterAnnouncement(
    val newCount: Int,
    val pluralLabel: String,
    val deepLinkChapterId: String?,
    val shouldNotify: Boolean,
)

/**
 * Decide what to announce for a fiction from the current un-downloaded
 * backlog and the chapter ids we held before this poll's refresh.
 *
 * The bug this fixes (Royal Road "65 new chapters"): the worker used the
 * size of [missingChapterIds] — every `downloadState = NOT_DOWNLOADED`
 * chapter — as the "new" count. A followed/SUBSCRIBE fiction never downloads
 * its chapters, so that set is the entire unplayed history, and each poll
 * re-announced the whole backlog. The genuine delta is the missing chapters
 * whose ids weren't already present before the refresh.
 *
 * First-poll baseline: an empty [priorChapterIds] means this is the first
 * time we've hydrated this fiction's chapters (e.g. a brand-new follow whose
 * row was added by `followsList` with no chapters yet). Everything looks new
 * but none of it is news to the user — they just followed it. We return the
 * delta count for telemetry but set [NewChapterAnnouncement.shouldNotify] to
 * false so the backlog isn't announced as new.
 *
 * Order is preserved from [missingChapterIds] (callers pass it sorted by
 * chapter index), so the deep-link target is the earliest new chapter.
 */
fun newChapterAnnouncement(
    missingChapterIds: List<String>,
    priorChapterIds: Set<String>,
): NewChapterAnnouncement {
    val newlyAdded = missingChapterIds.filter { it !in priorChapterIds }
    val isFirstPoll = priorChapterIds.isEmpty()
    val shouldNotify = newlyAdded.isNotEmpty() && !isFirstPoll
    return NewChapterAnnouncement(
        newCount = newlyAdded.size,
        pluralLabel = if (newlyAdded.size == 1) "chapter" else "chapters",
        deepLinkChapterId = newlyAdded.firstOrNull(),
        shouldNotify = shouldNotify,
    )
}
