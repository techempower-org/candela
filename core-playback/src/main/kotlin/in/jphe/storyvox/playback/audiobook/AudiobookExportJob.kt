package `in`.jphe.storyvox.playback.audiobook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Background WorkManager job that renders + encodes a fiction into a chaptered
 * `.m4b` (issue #1003). Mirrors `ChapterRenderJob`'s foreground-service shape
 * but produces a *shareable file* rather than a playback cache entry.
 *
 * Progress: publishes a 0..1 fraction via [setProgress] so the launching UI
 * (Library / Fiction detail) can render a determinate bar, and updates the
 * foreground notification text. On success, the output [Data] carries the
 * absolute path + FileProvider-able filename + chapter count so the observer
 * can fire the share / Save-As sheet.
 *
 * Resumability: a render that's interrupted (process death, user cancel)
 * simply re-runs from the start on retry — synthesis is deterministic and the
 * partial cache-dir file is overwritten. We don't checkpoint mid-encode; the
 * common case (a pasted article, a handful of chapters) completes in seconds
 * to a couple of minutes.
 */
@HiltWorker
class AudiobookExportJob @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted params: WorkerParameters,
    private val exportUseCase: ExportFictionToAudiobookUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val fictionId = inputData.getString(KEY_FICTION_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE).orEmpty()
        // Optional voice override id — null means "use the active voice".
        val voiceId = inputData.getString(KEY_VOICE_ID)

        Log.i(LOG_TAG, "audiobook-export START fictionId=$fictionId voiceId=$voiceId")
        runCatching { setForeground(foregroundInfo(title, progress = 0f)) }

        return try {
            val voice = voiceId?.let { exportUseCase.resolveVoice(it) }
            val result = exportUseCase.export(
                fictionId = fictionId,
                voiceOverride = voice,
            ) { fraction ->
                setProgressAsync(Data.Builder().putFloat(KEY_PROGRESS, fraction).build())
                runCatching { /* best-effort notif refresh */
                    setForegroundAsync(foregroundInfo(title, fraction))
                }
            }
            Log.i(
                LOG_TAG,
                "audiobook-export SUCCESS fictionId=$fictionId chapters=${result.chapterCount} " +
                    "file=${result.file.name}",
            )
            Result.success(
                Data.Builder()
                    .putString(KEY_OUT_PATH, result.file.absolutePath)
                    .putString(KEY_OUT_FILENAME, result.suggestedFileName)
                    .putInt(KEY_OUT_CHAPTERS, result.chapterCount)
                    .putStringArray(KEY_OUT_WARNINGS, result.warnings.toTypedArray())
                    .build(),
            )
        } catch (uns: AudiobookSynthesizer.UnsupportedVoiceException) {
            // Not retryable — the voice choice is wrong, retrying won't fix it.
            Log.w(LOG_TAG, "audiobook-export UNSUPPORTED-VOICE fictionId=$fictionId", uns)
            Result.failure(errorData(uns.message ?: "Unsupported voice"))
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // Top of the worker's execution tree: any escape marks the work
            // crashed. The native VoxSherpa layer can raise Error subtypes
            // (UnsatisfiedLinkError, OOM) a narrow catch would miss, so we
            // convert *everything* into a Failed status the UI can show.
            Log.w(LOG_TAG, "audiobook-export FAIL fictionId=$fictionId", t)
            if (isStopped) Result.failure() else Result.failure(errorData(t.message ?: "Export failed"))
        }
    }

    private fun errorData(message: String): Data =
        Data.Builder().putString(KEY_OUT_ERROR, message).build()

    private fun foregroundInfo(title: String, progress: Float): ForegroundInfo {
        val nm = appContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Audiobook export" }
            nm.createNotificationChannel(ch)
        }
        val pct = (progress.coerceIn(0f, 1f) * 100).toInt()
        val notif: Notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Creating audiobook")
            .setContentText(title.ifBlank { "Rendering chapters…" })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, pct, progress <= 0f)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        private const val LOG_TAG = "AudiobookExportJob"
        const val KEY_FICTION_ID = "fictionId"
        const val KEY_TITLE = "title"
        const val KEY_VOICE_ID = "voiceId"

        const val KEY_PROGRESS = "progress"

        const val KEY_OUT_PATH = "outPath"
        const val KEY_OUT_FILENAME = "outFilename"
        const val KEY_OUT_CHAPTERS = "outChapters"
        const val KEY_OUT_WARNINGS = "outWarnings"
        const val KEY_OUT_ERROR = "outError"

        private const val CHANNEL_ID = "audiobook-export-channel"
        private const val CHANNEL_NAME = "Audiobook export"
        private const val NOTIFICATION_ID = 5043
    }
}
