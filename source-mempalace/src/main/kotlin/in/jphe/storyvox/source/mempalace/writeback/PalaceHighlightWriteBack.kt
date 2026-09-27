package `in`.jphe.storyvox.source.mempalace.writeback

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.annotation.HighlightCapture
import `in`.jphe.storyvox.data.annotation.HighlightWriteBack
import `in`.jphe.storyvox.source.mempalace.config.PalaceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1468 — the Memory Palace implementation of [HighlightWriteBack].
 *
 * [onHighlightCreated] returns immediately: the opt-in check (a DataStore
 * read) and the WorkManager enqueue run on a private IO scope that outlives
 * the reader's ViewModel, so leaving the reader right after highlighting
 * can't cancel the capture, and nothing here ever runs on — or throws to —
 * the UI thread.
 *
 * Off by default: nothing is enqueued unless the palace host is configured
 * AND the user switched on Settings → Memory Palace → "Save highlights to
 * the palace" ([in.jphe.storyvox.source.mempalace.config.PalaceConfigState.isHighlightWriteBackActive]).
 */
@Singleton
class PalaceHighlightWriteBack @Inject constructor(
    @ApplicationContext private val context: Context,
    private val config: PalaceConfig,
) : HighlightWriteBack {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onHighlightCreated(capture: HighlightCapture) {
        scope.launch {
            try {
                if (!config.current().isHighlightWriteBackActive) return@launch
                WorkManager.getInstance(context).enqueueUniqueWork(
                    uniqueWorkName(capture.annotationId),
                    // KEEP: one highlight → at most one queued drawer write.
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<HighlightWriteBackWorker>()
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .setBackoffCriteria(
                            BackoffPolicy.EXPONENTIAL,
                            WriteBackRetryPolicy.INITIAL_BACKOFF_SECONDS,
                            TimeUnit.SECONDS,
                        )
                        .setInputData(inputData(capture))
                        .addTag(HighlightWriteBackWorker.TAG)
                        .build(),
                )
            } catch (e: Exception) {
                // A write-back is best-effort; never let it surface to the reader.
                Log.w(HighlightWriteBackWorker.TAG, "enqueue failed", e)
            }
        }
    }

    companion object {
        fun uniqueWorkName(annotationId: String): String =
            HighlightWriteBackWorker.UNIQUE_PREFIX + annotationId

        internal fun inputData(capture: HighlightCapture) = workDataOf(
            HighlightWriteBackWorker.KEY_CONTENT to HighlightDrawerPayload.content(capture),
            HighlightWriteBackWorker.KEY_ANNOTATION_ID to capture.annotationId,
        )
    }
}
