package `in`.jphe.storyvox.playback.transcribe.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice Notes (#1657, Phase 2b) — enqueues / cancels the durable
 * [TranscriptionWorker] for a note. Injected by whoever creates a recording
 * (Phase 2a) or retries from the UI (Phase 4); keeps WorkManager wiring out of
 * those callers.
 *
 * Unique-per-note (`KEEP`) so re-enqueuing a note already queued/running is a
 * no-op rather than a duplicate transcription. Exponential backoff for the
 * worker's retryable failures (model-load / decode transients).
 */
@Singleton
class TranscriptionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue(noteId: String) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_NOTE_ID to noteId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(noteId), ExistingWorkPolicy.KEEP, request)
    }

    /** Cancel an in-flight/queued transcription (worker resets the note to PENDING). */
    fun cancel(noteId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(noteId))
    }

    private fun workName(noteId: String): String = "$WORK_PREFIX$noteId"

    companion object {
        const val WORK_PREFIX: String = "notes-transcribe-"
    }
}
