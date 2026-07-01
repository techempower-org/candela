package `in`.jphe.storyvox.data.repository

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.log.DebugLog
import `in`.jphe.storyvox.data.work.ChapterDownloadWorker
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hands a single chapter-download job off to whatever background-work runtime
 * is wired up. Production binds [WorkManagerChapterDownloadScheduler]; tests
 * substitute a recording fake to assert call sequence + args without spinning
 * up WorkManager.
 *
 * Pulled out of [ChapterRepositoryImpl] so the repository no longer takes a
 * direct dependency on `androidx.work` — and so the queue-ordering invariants
 * (set state to QUEUED before enqueue; iterate `missingForFiction`'s output
 * for `queueAllMissing`; `requireUnmetered` plumbed through unchanged) become
 * unit-testable from the JVM.
 */
interface ChapterDownloadScheduler {
    fun schedule(fictionId: String, chapterId: String, requireUnmetered: Boolean)

    /**
     * Issue #1461 — cancel every not-yet-finished chapter download for one
     * fiction (bulk-download cancel). Cancels the WorkManager side only; the
     * caller ([ChapterRepository.cancelDownloads]) resets the DB rows so the
     * UI reflects the cancellation (WorkManager cancel doesn't touch Room).
     */
    fun cancelForFiction(fictionId: String)
}

@Singleton
class WorkManagerChapterDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ChapterDownloadScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun schedule(fictionId: String, chapterId: String, requireUnmetered: Boolean) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requireUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        val input = Data.Builder()
            .putString(ChapterDownloadWorker.KEY_FICTION_ID, fictionId)
            .putString(ChapterDownloadWorker.KEY_CHAPTER_ID, chapterId)
            .putBoolean(ChapterDownloadWorker.KEY_REQUIRE_UNMETERED, requireUnmetered)
            .build()

        val request = OneTimeWorkRequestBuilder<ChapterDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(input)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(30))
            .addTag(ChapterDownloadWorker.TAG)
            // Issue #1461 — per-fiction tag so a bulk download can be cancelled
            // fiction-at-a-time via cancelForFiction below.
            .addTag(ChapterDownloadWorker.fictionTag(fictionId))
            .build()

        android.util.Log.i("ChapterDownload", "schedule: chapter=$chapterId fiction=$fictionId unmetered=$requireUnmetered")
        DebugLog.i("ChapterDownloadScheduler") {
            "enqueueUniqueWork chapter=$chapterId fiction=$fictionId policy=REPLACE"
        }
        workManager.enqueueUniqueWork(
            ChapterDownloadWorker.uniqueName(chapterId),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override fun cancelForFiction(fictionId: String) {
        DebugLog.i("ChapterDownloadScheduler") { "cancelForFiction fiction=$fictionId" }
        workManager.cancelAllWorkByTag(ChapterDownloadWorker.fictionTag(fictionId))
    }
}
