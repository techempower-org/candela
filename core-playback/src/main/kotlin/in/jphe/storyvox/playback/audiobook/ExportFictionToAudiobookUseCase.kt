package `in`.jphe.storyvox.playback.audiobook

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.source.audiobook.writer.AudiobookFileName
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Result of [ExportFictionToAudiobookUseCase] — mirrors the EPUB writer's
 * `EpubExportResult`. Carries the finished `.m4b` plus a FileProvider URI for
 * the share / SAF-save flow and any non-fatal [warnings].
 */
data class AudiobookExportResult(
    val file: File,
    /** content:// URI granted via FileProvider — pass to ACTION_SEND /
     *  ACTION_CREATE_DOCUMENT. */
    val uri: Uri,
    val suggestedFileName: String,
    val chapterCount: Int,
    val warnings: List<String> = emptyList(),
)

/**
 * Renders a stored fiction to a chaptered `.m4b` audiobook in
 * `cacheDir/exports/` for sharing or SAF Save-As (issue #1003).
 *
 * The "missing piece" the issue calls out: storyvox synthesizes + caches PCM
 * for *playback* but never wrote a portable file. This use case closes that —
 * it mirrors `ExportFictionToEpubUseCase` (load DB rows → build → write to the
 * shared exports dir → FileProvider URI), substituting the audio pipeline
 * ([AudiobookSynthesizer] → [AacM4bEncoder]) for the EPUB ZIP writer.
 *
 * Threading: all on `Dispatchers.IO` — synthesis is CPU-bound and the encode
 * is blocking. The caller is the WorkManager [AudiobookExportJob], which sets
 * itself foreground for the (potentially many-minute) render.
 */
@Singleton
class ExportFictionToAudiobookUseCase @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
    private val voiceManager: VoiceManager,
    private val synthesizer: AudiobookSynthesizer,
) {
    private val encoder = AacM4bEncoder()

    private val coverClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .readTimeout(java.time.Duration.ofSeconds(10))
            .build()
    }

    /**
     * Build the audiobook for [fictionId] using [voiceOverride] (or the
     * active voice when null). [onProgress] reports an overall 0..1 fraction
     * across all chapters so the worker can update its notification.
     *
     * @throws IllegalStateException when there's no fiction row, no chapters
     *  with text, or no usable voice.
     * @throws AudiobookSynthesizer.UnsupportedVoiceException for Azure /
     *  System TTS voices.
     */
    suspend fun export(
        fictionId: String,
        voiceOverride: UiVoiceInfo? = null,
        onProgress: (Float) -> Unit = {},
    ): AudiobookExportResult = withContext(Dispatchers.IO) {
        val fiction = fictionDao.get(fictionId)
            ?: error("No Fiction row for id=$fictionId")

        val voice = voiceOverride
            ?: voiceManager.activeVoice.first()
            ?: error("No active voice — pick a voice before exporting an audiobook.")

        val allChapters = chapterDao.allChapters(fictionId)
        // Only chapters with narratable text. Skip audio-stream chapters
        // (they're pre-recorded URLs, not TTS) and empty placeholders.
        val renderable = allChapters
            .filter { it.audioUrl == null && !it.plainBody.isNullOrBlank() }
            .sortedBy { it.index }
        if (renderable.isEmpty()) {
            error("No downloaded chapter text to narrate for fiction $fictionId")
        }

        val warnings = mutableListOf<String>()
        val skipped = allChapters.size - renderable.size
        if (skipped > 0) {
            warnings += "$skipped chapter${if (skipped == 1) "" else "s"} skipped " +
                "(not downloaded or audio-only)"
        }

        // Load the model once up front (throws for unsupported voices).
        synthesizer.loadVoice(voice)
        val sampleRate = synthesizer.sampleRateFor(voice)

        // Render each chapter to PCM, reporting cumulative progress.
        val chapterAudio = ArrayList<AacM4bEncoder.ChapterAudio>(renderable.size)
        renderable.forEachIndexed { i, chapter ->
            val pcm = synthesizer.renderChapter(
                voice = voice,
                text = chapter.plainBody.orEmpty(),
            ) { withinChapter ->
                val overall = (i + withinChapter) / renderable.size
                onProgress(overall)
            }
            if (pcm.isNotEmpty()) {
                chapterAudio += AacM4bEncoder.ChapterAudio(
                    title = chapter.title.ifBlank { "Chapter ${chapter.index + 1}" },
                    pcm = pcm,
                )
            }
        }
        if (chapterAudio.isEmpty()) {
            error("Synthesis produced no audio for fiction $fictionId")
        }

        val cover = fiction.coverUrl?.let { url ->
            runCatching { fetchCover(url) }.getOrElse {
                warnings += "Cover couldn't be downloaded (${it.javaClass.simpleName})"
                null
            }
        }

        val outDir = File(appContext.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val fileName = AudiobookFileName.forTitle(
            fiction.title.ifBlank { fiction.id }, stamp,
        )
        val outFile = File(outDir, fileName)

        encoder.encode(
            chapters = chapterAudio,
            sampleRate = sampleRate,
            title = fiction.title.ifBlank { "Untitled" },
            author = fiction.author.ifBlank { "Unknown" },
            cover = cover,
            outFile = outFile,
        )
        onProgress(1f)

        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            outFile,
        )

        AudiobookExportResult(
            file = outFile,
            uri = uri,
            suggestedFileName = fileName,
            chapterCount = chapterAudio.size,
            warnings = warnings,
        )
    }

    /**
     * Resolve a voice id to its [UiVoiceInfo] from the installed roster, or
     * null if it's not currently installed. Used by [AudiobookExportJob] to
     * turn the persisted "render with this voice" id (the user's pick in the
     * Create-audiobook flow) into the voice the synthesizer loads. Falls back
     * to the active voice at the call site when this returns null.
     */
    suspend fun resolveVoice(voiceId: String): UiVoiceInfo? =
        voiceManager.installedVoices.first().firstOrNull { it.id == voiceId }

    private fun fetchCover(url: String): ByteArray? {
        val req = Request.Builder().url(url).build()
        coverClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }
}
