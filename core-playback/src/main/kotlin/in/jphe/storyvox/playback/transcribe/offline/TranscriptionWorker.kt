package `in`.jphe.storyvox.playback.transcribe.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import `in`.jphe.storyvox.playback.R as PlaybackR
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import `in`.jphe.storyvox.data.notes.NoteEntity
import `in`.jphe.storyvox.data.notes.NotesRepository
import `in`.jphe.storyvox.data.notes.TranscriptionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Voice Notes (#1657, Phase 2b) — durable, background transcription of a
 * recorded note. A **`WorkManager` job backed by a foreground service** (not a
 * bare coroutine): a 1 h meeting must survive app backgrounding / process death,
 * so it runs under an FGS and is resumable + cancellable (spec §3.2, §4).
 *
 * Flow: read the note → if no audio or model-not-downloaded, leave status
 * `PENDING` and succeed (Phase 4 offers the download); else status
 * `RUNNING` → stream punctuated segments from [OfflineTranscriber] into the
 * transcript **as they complete** (bounded memory — text only) → `DONE`.
 * Failure → `FAILED` + retry with backoff (capped); deliberate cancel →
 * back to `PENDING`. The audio + any manual body are always retained.
 *
 * Mirrors `ChapterRenderJob`'s `@HiltWorker` + `DATA_SYNC` FGS shape.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val notes: NotesRepository,
    private val transcriber: OfflineTranscriber,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val noteId = inputData.getString(KEY_NOTE_ID) ?: return Result.failure()
        val note = notes.get(noteId) ?: return Result.success() // deleted mid-flight → nothing to do
        val audioPath = note.audioPath ?: return Result.success() // typed note → no transcription

        if (!transcriber.isModelReady()) {
            // Keep the audio; leave PENDING so a later download can re-enqueue.
            Log.i(TAG, "model not ready — leaving note $noteId PENDING")
            return Result.success()
        }

        runCatching { setForeground(buildForegroundInfo()) } // survive backgrounding
        writeStatus(noteId, TranscriptionStatus.RUNNING)

        return try {
            val sb = StringBuilder()
            transcriber.transcribe(audioPath, note.transcriptLang).collect { seg ->
                if (seg.text.isBlank()) return@collect
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(seg.text.trim())
                writeTranscript(noteId, sb.toString(), TranscriptionStatus.RUNNING)
            }
            writeTranscript(noteId, sb.toString(), TranscriptionStatus.DONE)
            Result.success()
        } catch (ce: CancellationException) {
            // Deliberate cancel — reset to PENDING off the cancelled scope.
            withContext(NonCancellable) { writeStatus(noteId, TranscriptionStatus.PENDING) }
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "transcription failed for $noteId: ${t.message}")
            writeStatus(noteId, TranscriptionStatus.FAILED)
            if (runAttemptCount + 1 >= MAX_ATTEMPTS) Result.failure() else Result.retry()
        }
    }

    // #1663 — column-scoped writes (transcript/status only). Disjoint from the
    // detail-screen edit's write set (title/body/tags), so a concurrent user
    // edit can't be clobbered — no read-modify-write of the full row.
    private suspend fun writeStatus(noteId: String, status: TranscriptionStatus) {
        runCatching { notes.updateTranscriptionStatus(noteId, status, now()) }
    }

    private suspend fun writeTranscript(noteId: String, transcript: String, status: TranscriptionStatus) {
        runCatching { notes.updateTranscription(noteId, transcript, status, now()) }
    }

    private fun now(): Long = System.currentTimeMillis()

    private fun buildForegroundInfo(): ForegroundInfo {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm?.getNotificationChannel(CHANNEL_ID) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notif: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(PlaybackR.drawable.ic_storyvox_notif)
            .setContentTitle(appContext.getString(PlaybackR.string.transcription_notif_title))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        const val KEY_NOTE_ID = "noteId"
        private const val TAG = "TranscriptionWorker"
        private const val MAX_ATTEMPTS = 3
        private const val CHANNEL_ID = "notes-transcription"
        private const val CHANNEL_NAME = "Note transcription"
        private const val NOTIFICATION_ID = 0x0107 // #1657
    }
}
