package `in`.jphe.storyvox.source.mempalace.writeback

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi

/**
 * Issue #1468 — files one highlight drawer into the palace (`POST /memory`).
 *
 * Enqueued by [PalaceHighlightWriteBack] with a `CONNECTED` network
 * constraint and exponential backoff, under a unique per-highlight name, so
 * a highlight made off the LAN (or while the palace host is asleep) is
 * delivered when the phone is next reachable. The drawer body is built at
 * enqueue time and carried in the input [androidx.work.Data]; the worker
 * only transports it.
 *
 * The opt-in is re-checked here: if the user turned write-back off (or
 * cleared the palace host) while this was queued, the capture is dropped
 * rather than sent. Outcome → [WriteBackRetryPolicy].
 */
@HiltWorker
class HighlightWriteBackWorker @AssistedInject internal constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val api: PalaceDaemonApi,
    private val config: PalaceConfig,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val content = inputData.getString(KEY_CONTENT)
        if (content.isNullOrBlank()) return Result.failure()
        if (!config.current().isHighlightWriteBackActive) {
            Log.w(TAG, "write-back disabled or palace unconfigured since enqueue; dropping")
            return Result.success()
        }
        val result = api.storeMemory(HighlightDrawerPayload.requestBody(content))
        return when (WriteBackRetryPolicy.decide(result, runAttemptCount)) {
            WriteBackRetryPolicy.Decision.Done -> Result.success()
            WriteBackRetryPolicy.Decision.Retry -> Result.retry()
            WriteBackRetryPolicy.Decision.GiveUp -> {
                Log.w(TAG, "giving up after attempt ${runAttemptCount + 1}: ${result::class.simpleName}")
                Result.failure()
            }
        }
    }

    companion object {
        const val TAG: String = "PalaceHighlightWB"
        const val KEY_CONTENT: String = "content"
        const val KEY_ANNOTATION_ID: String = "annotationId"
        /** Unique-work name prefix; the suffix is the annotation UUID. */
        const val UNIQUE_PREFIX: String = "palace-highlight:"
    }
}
