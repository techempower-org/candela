package `in`.jphe.storyvox.playback.transcribe.offline

import android.content.Context
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Voice Notes (#1657, Phase 2b) — owns the on-device **offline** Whisper model
 * that backs [SherpaOfflineTranscriber]. Mirrors
 * [AsrModelProvider][`in`.jphe.storyvox.playback.transcribe.AsrModelProvider]'s
 * download-to-disk + load-by-path pattern, but in a **separate** cache dir
 * (`filesDir/asr-offline/<MODEL_ID>/`) so it never collides with the streaming
 * teleprompter model.
 *
 * Default: **Whisper base int8** (multilingual — EN+ES + native punctuation,
 * MIT), the only packaged option meeting Candela's bilingual bar (nebula's
 * model research). Downloaded on first use (~200 MB — too big to bundle).
 *
 * ### URLs/sizes — CONFIRM before release (#1657)
 * The [SPEC] host + filenames follow the csukuangfj HuggingFace whisper layout
 * and the byte sizes are ballpark (nebula flagged them). A wrong URL surfaces
 * as [DownloadProgress.Failed] → the note simply stays `PENDING` (graceful
 * degrade), never a crash — same contract as `AsrModelProvider`. Verify the
 * exact int8 asset names + sizes on-device before shipping a storage budget.
 */
@Singleton
class TranscriptionModelProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    @VisibleForTesting
    internal var http: OkHttpClient = OkHttpClient.Builder().build()

    private val modelDir: File get() = File(context.filesDir, "asr-offline/$MODEL_ID")

    /** True once every model file is present and non-empty. */
    fun isReady(): Boolean = SPEC.all { f ->
        val local = File(modelDir, f.localName)
        local.exists() && local.length() > 0L
    }

    /** Absolute paths for the recognizer, or null if not fully downloaded. */
    fun readyModel(): OfflineAsrModel? {
        if (!isReady()) return null
        fun path(name: String) = File(modelDir, name).absolutePath
        return OfflineAsrModel(encoder = path(ENCODER), decoder = path(DECODER), tokens = path(TOKENS))
    }

    /** Stream a download of the model (mirrors AsrModelProvider). Idempotent. */
    fun download(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Resolving)
        if (isReady()) { emit(DownloadProgress.Done); return@flow }
        modelDir.mkdirs()
        val total = SPEC.sumOf { it.approxBytes }
        var done = 0L
        try {
            for (f in SPEC) {
                val target = File(modelDir, f.localName)
                if (target.exists() && target.length() > 0L) {
                    done += f.approxBytes
                    emit(DownloadProgress.Downloading(done, total))
                    continue
                }
                val base = done
                downloadFile(f.url, target) { read -> emit(DownloadProgress.Downloading(base + read, total)) }
                done += f.approxBytes
            }
            emit(DownloadProgress.Done)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            emit(DownloadProgress.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun downloadFile(url: String, target: File, onBytes: suspend (Long) -> Unit) {
        val part = File(target.parentFile, target.name + ".part")
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            val body = resp.body ?: error("empty body for $url")
            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        onBytes(read)
                    }
                }
            }
        }
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
    }

    private data class Asset(val url: String, val localName: String, val approxBytes: Long)

    /** Download progress frames (mirrors AsrModelProvider.DownloadProgress). */
    sealed interface DownloadProgress {
        data object Resolving : DownloadProgress
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : DownloadProgress
        data object Done : DownloadProgress
        data class Failed(val reason: String) : DownloadProgress
    }

    companion object {
        /** Bump if the model is swapped — isolates the on-disk cache dir. */
        const val MODEL_ID: String = "whisper-base-int8-multilingual"

        private const val ENCODER = "base-encoder.int8.onnx"
        private const val DECODER = "base-decoder.int8.onnx"
        private const val TOKENS = "base-tokens.txt"

        /** Ballpark (nebula-flagged) — confirm before a storage budget. */
        const val APPROX_TOTAL_MB: Int = 200

        // csukuangfj HuggingFace multilingual whisper-base layout — CONFIRM on-device (see kdoc).
        private const val HOST =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-base/resolve/main"

        private val SPEC: List<Asset> = listOf(
            Asset("$HOST/$ENCODER", ENCODER, 100_000_000L),
            Asset("$HOST/$DECODER", DECODER, 100_000_000L),
            Asset("$HOST/$TOKENS", TOKENS, 500_000L),
        )
    }
}

/**
 * Absolute paths to a downloaded Whisper model (encoder + decoder + tokens —
 * no joiner, unlike the streaming transducer). Produced by
 * [TranscriptionModelProvider.readyModel], consumed by [SherpaOfflineTranscriber].
 */
data class OfflineAsrModel(
    val encoder: String,
    val decoder: String,
    val tokens: String,
)
