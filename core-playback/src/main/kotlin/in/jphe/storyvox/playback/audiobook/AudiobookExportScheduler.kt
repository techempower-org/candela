package `in`.jphe.storyvox.playback.audiobook

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * UI-facing status of an in-flight or finished audiobook export. Surfaced by
 * [AudiobookExportScheduler.statusFor] so the launching screen can show a
 * determinate progress bar and then the Share / Save-As sheet (issue #1003).
 */
sealed interface AudiobookExportStatus {
    data object Idle : AudiobookExportStatus
    data class Running(val progress: Float) : AudiobookExportStatus
    data class Succeeded(
        val filePath: String,
        val fileName: String,
        val chapterCount: Int,
        val warnings: List<String>,
    ) : AudiobookExportStatus
    data class Failed(val message: String) : AudiobookExportStatus
}

/**
 * Enqueues [AudiobookExportJob] and projects its WorkManager state into
 * [AudiobookExportStatus]. Keeps `androidx.work` imports out of the feature
 * layer, mirroring `PcmRenderScheduler`.
 *
 * Unique-name policy: one export per fiction in flight (`KEEP`), so a
 * double-tap doesn't kick off two encodes.
 */
@Singleton
class AudiobookExportScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    /**
     * Enqueue an export of [fictionId] using [voiceId] (null = active voice).
     * Returns the unique work name, which the caller passes to [statusFor].
     */
    fun enqueue(fictionId: String, title: String, voiceId: String?): String {
        val constraints = Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()
        val input = Data.Builder()
            .putString(AudiobookExportJob.KEY_FICTION_ID, fictionId)
            .putString(AudiobookExportJob.KEY_TITLE, title)
            .apply { if (voiceId != null) putString(AudiobookExportJob.KEY_VOICE_ID, voiceId) }
            .build()
        val request = OneTimeWorkRequestBuilder<AudiobookExportJob>()
            .setConstraints(constraints)
            .setInputData(input)
            .addTag(TAG)
            .build()
        val name = uniqueName(fictionId)
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
        return name
    }

    /** Observe the export's lifecycle for [uniqueName]. */
    fun statusFor(uniqueName: String): Flow<AudiobookExportStatus> =
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName).map { infos ->
            val info = infos.lastOrNull() ?: return@map AudiobookExportStatus.Idle
            when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED,
                -> AudiobookExportStatus.Running(0f)
                WorkInfo.State.RUNNING ->
                    AudiobookExportStatus.Running(info.progress.getFloat(AudiobookExportJob.KEY_PROGRESS, 0f))
                WorkInfo.State.SUCCEEDED -> {
                    val out = info.outputData
                    val path = out.getString(AudiobookExportJob.KEY_OUT_PATH)
                    if (path == null) {
                        AudiobookExportStatus.Failed("Export finished without a file")
                    } else {
                        AudiobookExportStatus.Succeeded(
                            filePath = path,
                            fileName = out.getString(AudiobookExportJob.KEY_OUT_FILENAME).orEmpty(),
                            chapterCount = out.getInt(AudiobookExportJob.KEY_OUT_CHAPTERS, 0),
                            warnings = out.getStringArray(AudiobookExportJob.KEY_OUT_WARNINGS)
                                ?.toList().orEmpty(),
                        )
                    }
                }
                WorkInfo.State.FAILED ->
                    AudiobookExportStatus.Failed(
                        info.outputData.getString(AudiobookExportJob.KEY_OUT_ERROR)
                            ?: "Couldn't create the audiobook",
                    )
                WorkInfo.State.CANCELLED -> AudiobookExportStatus.Idle
            }
        }

    /** Cancel an in-flight export. */
    fun cancel(fictionId: String) {
        workManager.cancelUniqueWork(uniqueName(fictionId))
    }

    companion object {
        const val TAG = "audiobook-export"
        fun uniqueName(fictionId: String): String = "audiobook-export-$fictionId"
    }
}
