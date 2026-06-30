package `in`.jphe.storyvox.playback.cache

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules background PCM cache renders for chapters likely to be played
 * soon. Sits in front of WorkManager so trigger sources (FictionRepository's
 * addToLibrary, EnginePlayer's handleChapterDone, the `:app`-level Mode C
 * collector) stay free of `androidx.work` imports.
 *
 * Production binds [WorkManagerPcmRenderScheduler]; tests substitute a
 * recording fake to assert call sequence + args without spinning up
 * WorkManager (see `PrerenderTriggersTest`).
 *
 * Unique-name policy: `pcm-render-<chapterId>` with
 * [ExistingWorkPolicy.KEEP] — repeated calls for the same chapter are
 * no-ops while a prior request is queued or running. This is the spec's
 * "single in-flight render at a time per chapter" enforcement at the
 * WorkManager level. Process-wide engine concurrency is enforced
 * separately by [EngineMutex] inside the worker.
 *
 * Cancellation paths:
 *  - [cancelRender] — fine-grained by chapterId. Used when foreground
 *    playback of the same key is about to start (the streaming source's
 *    tee will populate the cache anyway).
 *  - [cancelAllForFiction] — bulk cancel via the `pcm-render-fiction-<id>`
 *    tag. Used by `FictionRepository.removeFromLibrary` to stop
 *    background work for a fiction the user no longer wants.
 *
 * PR F of the PCM cache series (#86).
 */
interface PcmRenderScheduler {

    /**
     * Enqueue a render for [chapterId]. No-op if a render for the same
     * chapterId is already queued or running (KEEP policy).
     */
    fun scheduleRender(fictionId: String, chapterId: String)

    /** Cancel an in-flight or queued render for [chapterId]. Idempotent. */
    fun cancelRender(chapterId: String)

    /**
     * Cancel all renders for any chapter belonging to [fictionId]. Used
     * by [`in`.jphe.storyvox.data.repository.FictionRepository.removeFromLibrary]
     * (via the FictionLibraryListener seam) to stop background work for
     * a fiction the user no longer wants.
     */
    fun cancelAllForFiction(fictionId: String)
}

@Singleton
class WorkManagerPcmRenderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : PcmRenderScheduler {

    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    override fun scheduleRender(fictionId: String, chapterId: String) {
        // Per spec — don't render at low battery (synthesis is CPU-heavy,
        // ~30%+ on Helio P22T per chapter) or when storage is low (the
        // cache writes can be 70+ MB per chapter on Piper-high; a render
        // that gets killed mid-write by ENOSPC leaves a partial file
        // for the next foreground play to wipe via PR-D's abandon-and-
        // restart policy — works, but wasteful). NetworkType.NOT_REQUIRED
        // because synthesis is purely local (Azure renders are excluded
        // from background pre-render in PR-F's first cut — they round-
        // trip to the cloud and BYOK rate limits make unsupervised
        // pre-render risky).
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()

        val input = Data.Builder()
            .putString(ChapterRenderJob.KEY_FICTION_ID, fictionId)
            .putString(ChapterRenderJob.KEY_CHAPTER_ID, chapterId)
            .build()

        val request = OneTimeWorkRequestBuilder<ChapterRenderJob>()
            .setConstraints(constraints)
            .setInputData(input)
            // #1392 — delay so the model load doesn't overlap with app
            // startup. On memory-constrained Samsung devices, an
            // immediate sherpa-onnx load triggers the Low Memory Killer.
            .setInitialDelay(Duration.ofSeconds(30))
            // Long backoff — a render that fails (model load failure,
            // OOM mid-generate) is unlikely to succeed on a quick retry.
            // Give the device time to clean up; if it keeps failing, the
            // WorkManager backoff cap takes over.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofMinutes(5))
            .addTag(TAG)
            .addTag(fictionTag(fictionId))
            .build()

        Log.i(
            LOG_TAG,
            "pcm-cache PRERENDER-ENQUEUED fictionId=$fictionId chapterId=$chapterId",
        )
        workManager.enqueueUniqueWork(
            uniqueName(chapterId),
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    override fun cancelRender(chapterId: String) {
        Log.i(LOG_TAG, "pcm-cache PRERENDER-CANCEL chapterId=$chapterId")
        workManager.cancelUniqueWork(uniqueName(chapterId))
    }

    override fun cancelAllForFiction(fictionId: String) {
        Log.i(LOG_TAG, "pcm-cache PRERENDER-CANCEL-FICTION fictionId=$fictionId")
        workManager.cancelAllWorkByTag(fictionTag(fictionId))
    }

    companion object {
        /** Logcat tag — match the rest of the PCM cache layer's scheme. */
        private const val LOG_TAG = "PcmRenderScheduler"

        /** Shared WorkManager tag for *every* PCM render job; lets a
         *  blanket cancelAllWorkByTag(TAG) wipe pre-renders without
         *  also nuking ChapterDownloadWorker entries. */
        const val TAG = "pcm-render"

        fun uniqueName(chapterId: String): String = "pcm-render-$chapterId"
        fun fictionTag(fictionId: String): String = "pcm-render-fiction-$fictionId"
    }
}
