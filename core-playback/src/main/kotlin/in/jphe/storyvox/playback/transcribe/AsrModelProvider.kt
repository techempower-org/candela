package `in`.jphe.storyvox.playback.transcribe

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
 * Issue #1368 — owns the on-device streaming-ASR model that backs the
 * voice-paced teleprompter ([MicCaptureProcessor]). The model is **not**
 * bundled (it would bloat the APK and most users never enable voice-follow),
 * so it's downloaded on first use into `filesDir/asr/<MODEL_ID>/`, mirroring
 * how [`in`.jphe.storyvox.playback.voice.VoiceManager] streams TTS voices.
 *
 * Resolution is filesystem-only: [readyModel] hands [MicCaptureProcessor]
 * absolute file paths, and the sherpa-onnx `OnlineRecognizer` loads them
 * directly (no `AssetManager`). [isReady] is the single gate the reader
 * checks before offering voice-follow; when false it offers [download], and
 * when a download fails the whole feature degrades to the manual-WPM
 * teleprompter rather than erroring.
 *
 * ### Model choice (device-validation, #1368)
 * Defaults to the compact **streaming-zipformer-en-20M** (~20 MB int8) — the
 * smallest English streaming transducer sherpa ships, chosen so first-use
 * download and per-frame inference stay light on a phone. The exact asset
 * filenames + host below are the upstream csukuangfj HuggingFace layout and
 * should be confirmed on-device (latency, accuracy, and whether to repackage
 * onto the project's own release for flat URLs, as the Kitten TTS model was).
 * A wrong URL surfaces as [DownloadProgress.Failed] → graceful fallback, never
 * a crash.
 */
@Singleton
class AsrModelProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Overridable in tests; real network by default (mirrors VoiceManager). */
    @VisibleForTesting
    internal var http: OkHttpClient = OkHttpClient.Builder().build()

    private val modelDir: File get() = File(context.filesDir, "asr/$MODEL_ID")

    /** True once every model file is present and non-empty. */
    fun isReady(): Boolean = SPEC.all { f ->
        val local = File(modelDir, f.localName)
        local.exists() && local.length() > 0L
    }

    /**
     * The resolved model's absolute paths, or `null` if not fully downloaded.
     * [MicCaptureProcessor] feeds these straight into the recognizer config.
     */
    fun readyModel(): AsrModel? {
        if (!isReady()) return null
        fun path(name: String) = File(modelDir, name).absolutePath
        return AsrModel(
            encoder = path(ENCODER),
            decoder = path(DECODER),
            joiner = path(JOINER),
            tokens = path(TOKENS),
        )
    }

    /**
     * Stream a download of the model. Emits [DownloadProgress.Resolving],
     * then [Downloading] frames as bytes land across all files, then a single
     * terminal [Done] or [Failed]. Each file streams to a `.part` sibling and
     * is renamed on completion, so an interrupted download never leaves a
     * truncated file that [isReady] would mistake for installed. Idempotent:
     * files already present are skipped.
     */
    fun download(): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Resolving)
        if (isReady()) {
            emit(DownloadProgress.Done)
            return@flow
        }
        modelDir.mkdirs()
        val totalBytes = SPEC.sumOf { it.approxBytes }
        var doneBytes = 0L
        try {
            for (f in SPEC) {
                val target = File(modelDir, f.localName)
                if (target.exists() && target.length() > 0L) {
                    doneBytes += f.approxBytes
                    emit(DownloadProgress.Downloading(doneBytes, totalBytes))
                    continue
                }
                val base = doneBytes
                downloadFile(f.url, target) { readThisFile ->
                    emit(DownloadProgress.Downloading(base + readThisFile, totalBytes))
                }
                doneBytes += f.approxBytes
            }
            emit(DownloadProgress.Done)
        } catch (ce: CancellationException) {
            // User-driven cancel — honour structured concurrency. Completed
            // files survive (no wipe), so a retry resumes file-by-file.
            throw ce
        } catch (t: Throwable) {
            emit(DownloadProgress.Failed(t.message ?: t.javaClass.simpleName))
        }
    }.flowOn(Dispatchers.IO) // blocking HTTP + file IO must stay off the collector's thread (#585)

    /** Stream [url] to a `.part` file then atomically rename to [target]. */
    private suspend fun downloadFile(
        url: String,
        target: File,
        onBytes: suspend (readSoFar: Long) -> Unit,
    ) {
        val part = File(target.parentFile, target.name + ".part")
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code} for $url")
            val body = resp.body ?: error("Empty body for $url")
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
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /** One model asset: where to fetch it, what to call it locally, rough size. */
    private data class Asset(val url: String, val localName: String, val approxBytes: Long)

    /** Progress frames for a model [download] — mirrors VoiceManager's shape. */
    sealed interface DownloadProgress {
        data object Resolving : DownloadProgress
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : DownloadProgress
        data object Done : DownloadProgress
        data class Failed(val reason: String) : DownloadProgress
    }

    companion object {
        /** Bumped if the model is swapped — isolates the on-disk cache dir. */
        const val MODEL_ID: String = "streaming-zipformer-en-20m-2023-02-17"

        private const val ENCODER = "encoder.onnx"
        private const val DECODER = "decoder.onnx"
        private const val JOINER = "joiner.onnx"
        private const val TOKENS = "tokens.txt"

        /** Approximate total download, surfaced in the UI before resolving. */
        const val APPROX_TOTAL_MB: Int = 22

        /**
         * Upstream csukuangfj HuggingFace layout for the 20M int8 streaming
         * zipformer. `resolve/main` gives flat per-file URLs the OkHttp loop
         * can stream (no in-app tar extraction). See the class kdoc — confirm
         * on-device before release.
         */
        private const val HOST =
            "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17/resolve/main"

        private val SPEC: List<Asset> = listOf(
            Asset("$HOST/encoder-epoch-99-avg-1.int8.onnx", ENCODER, 12_000_000L),
            Asset("$HOST/decoder-epoch-99-avg-1.onnx", DECODER, 2_000_000L),
            Asset("$HOST/joiner-epoch-99-avg-1.int8.onnx", JOINER, 8_000_000L),
            Asset("$HOST/tokens.txt", TOKENS, 5_000L),
        )
    }
}

/**
 * Absolute filesystem paths to a downloaded streaming-ASR model — the four
 * files sherpa-onnx's transducer recognizer needs. Produced by
 * [AsrModelProvider.readyModel], consumed by [MicCaptureProcessor].
 */
data class AsrModel(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String,
)
