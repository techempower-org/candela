package `in`.jphe.storyvox.playback.audiobook

import android.content.Context
import com.CodeBySonu.VoxSherpa.KittenEngine
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.cache.EngineMutex
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import `in`.jphe.storyvox.playback.tts.detectLocale
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceManager
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.withLock

/**
 * Renders a chapter's text to one continuous block of 16-bit-mono PCM using
 * the in-process VoxSherpa engines (issue #1003 — "Make your own audiobook").
 *
 * This is the export counterpart to
 * [`in`.jphe.storyvox.playback.cache.ChapterRenderJob], which renders into the
 * per-sentence PCM *cache*. The export wants the whole chapter as a single PCM
 * buffer to feed the AAC encoder, so we synthesize sentence-by-sentence (the
 * engines work best on sentence-sized inputs) and concatenate, inserting short
 * trailing silence between sentences so the narration doesn't run together.
 *
 * Engine concurrency mirrors the cache renderer: [EngineMutex.mutex] is held
 * around `loadModel` and every `generateAudioPCM` so a foreground playback or
 * a background cache render can't interleave model state with ours.
 *
 * Only the local engines (Piper / Kokoro / Kitten / Supertonic) are supported — the export
 * flow defaults to a local voice and is offline-first per the issue. Azure
 * (cloud, BYOK, rate-limited) and System TTS (framework binder on the OS
 * process) are rejected with a typed error so the caller can surface a clear
 * "pick a local voice" message rather than producing a silent file.
 */
@Singleton
class AudiobookSynthesizer @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val voiceManager: VoiceManager,
    private val engineMutex: EngineMutex,
    private val chunker: SentenceChunker,
) {

    /** Raised when the chosen voice can't be rendered offline by this path. */
    class UnsupportedVoiceException(message: String) : IllegalStateException(message)

    /** Raised when the engine model couldn't be loaded. */
    class EngineLoadException(message: String) : IllegalStateException(message)

    /** PCM sample rate (Hz) the active voice's engine reports. The encoder
     *  needs this to configure the AAC format; it's stable for a loaded
     *  model so we resolve it once after [loadVoice]. */
    fun sampleRateFor(voice: UiVoiceInfo): Int =
        when (voice.engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().sampleRate
            is EngineType.Kitten -> KittenEngine.getInstance().sampleRate
            // TODO(#1114): replace with SupertonicEngine.getInstance().sampleRate
            // when VoxSherpa v2.9.0 ships. Uses the cache default (24 kHz) for now.
            is EngineType.Supertonic -> EngineSampleRateCache.supertonicRate()
            else -> VoiceEngine.getInstance().sampleRate
        }.takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE_HZ

    /**
     * Load [voice]'s model. Idempotent if the same model is already loaded
     * (the engine no-ops a redundant load). Must be called before
     * [renderChapter]. Holds [EngineMutex.mutex].
     *
     * @throws UnsupportedVoiceException for Azure / System TTS voices.
     * @throws EngineLoadException when the native load fails.
     */
    suspend fun loadVoice(voice: UiVoiceInfo) {
        when (voice.engineType) {
            is EngineType.Azure -> throw UnsupportedVoiceException(
                "Azure voices need a network round-trip and can't be used for " +
                    "offline audiobook export — pick a downloaded voice.",
            )
            is EngineType.SystemTts -> throw UnsupportedVoiceException(
                "System (device) voices can't be exported to a file — pick a " +
                    "downloaded Piper, Kokoro, Kitten or Supertonic voice.",
            )
            else -> Unit
        }
        val result = engineMutex.mutex.withLock { loadModel(voice) }
        if (result != "Success") throw EngineLoadException("Voice failed to load: $result")
    }

    /**
     * Render [text] to a single PCM buffer (16-bit mono LE at the engine's
     * sample rate). Returns an empty array for blank text. [onProgress] is
     * invoked with a 0..1 fraction as sentences complete so a long chapter
     * can update a progress notification.
     */
    suspend fun renderChapter(
        voice: UiVoiceInfo,
        text: String,
        onProgress: (Float) -> Unit = {},
    ): ByteArray {
        if (text.isBlank()) return ByteArray(0)
        val sentences = chunker.chunk(text, detectLocale(text))
        if (sentences.isEmpty()) return ByteArray(0)

        val sampleRate = sampleRateFor(voice)
        val silence = silenceBytes(SENTENCE_GAP_MS, sampleRate)
        val out = ByteArrayOutputStream()
        for ((i, sentence) in sentences.withIndex()) {
            val pcm = engineMutex.mutex.withLock { generateAudioPCM(voice, sentence.text) }
            if (pcm != null && pcm.isNotEmpty()) {
                out.write(pcm)
                out.write(silence)
            }
            onProgress((i + 1).toFloat() / sentences.size)
        }
        return out.toByteArray()
    }

    // ── engine bridging (mirrors ChapterRenderJob) ──────────────────────────

    private fun loadModel(voice: UiVoiceInfo): String =
        when (val type = voice.engineType) {
            is EngineType.Piper -> {
                val voiceDir = voiceManager.voiceDirFor(voice.id)
                val onnx = File(voiceDir, MODEL_FILE).absolutePath
                val tokens = File(voiceDir, TOKENS_FILE).absolutePath
                VoiceEngine.getInstance().loadModel(appContext, onnx, tokens)
                    ?: ERR_LOAD_NULL
            }
            is EngineType.Kokoro -> {
                val sharedDir = voiceManager.kokoroSharedDir()
                val onnx = File(sharedDir, MODEL_FILE).absolutePath
                val tokens = File(sharedDir, TOKENS_FILE).absolutePath
                val voicesBin = File(sharedDir, VOICES_BIN_FILE).absolutePath
                KokoroEngine.getInstance().setActiveSpeakerId(type.speakerId)
                KokoroEngine.getInstance().loadModel(appContext, onnx, tokens, voicesBin)
                    ?: ERR_LOAD_NULL
            }
            is EngineType.Kitten -> {
                val sharedDir = voiceManager.kittenSharedDir()
                val onnx = File(sharedDir, MODEL_FILE).absolutePath
                val tokens = File(sharedDir, TOKENS_FILE).absolutePath
                val voicesBin = File(sharedDir, VOICES_BIN_FILE).absolutePath
                KittenEngine.getInstance().setActiveSpeakerId(type.speakerId)
                KittenEngine.getInstance().loadModel(appContext, onnx, tokens, voicesBin)
                    ?: ERR_LOAD_NULL
            }
            // TODO(#1114): wire SupertonicEngine.loadModel when VoxSherpa v2.9.0 ships.
            is EngineType.Supertonic ->
                "Error: Supertonic engine not yet available (needs VoxSherpa v2.9.0)"
            // Guarded in loadVoice; defensive typed errors keep the when exhaustive.
            is EngineType.Azure -> "Error: Azure not supported for export"
            is EngineType.SystemTts -> "Error: System TTS not supported for export"
        }

    private fun generateAudioPCM(voice: UiVoiceInfo, text: String): ByteArray? =
        when (voice.engineType) {
            is EngineType.Kokoro -> KokoroEngine.getInstance().generateAudioPCM(text, 1.0f, 1.0f)
            is EngineType.Kitten -> KittenEngine.getInstance().generateAudioPCM(text, 1.0f, 1.0f)
            // TODO(#1114): SupertonicEngine.getInstance().generateAudioPCM(text, 1.0f, 1.0f)
            is EngineType.Supertonic -> null
            is EngineType.Azure, is EngineType.SystemTts -> null
            else -> VoiceEngine.getInstance().generateAudioPCM(text, 1.0f, 1.0f)
        }

    /** A block of silence: 16-bit mono zero samples for [ms] milliseconds. */
    private fun silenceBytes(ms: Int, sampleRate: Int): ByteArray {
        val samples = (sampleRate.toLong() * ms / 1000L).toInt()
        return ByteArray(samples * BYTES_PER_SAMPLE) // zeroed
    }

    companion object {
        private const val DEFAULT_SAMPLE_RATE_HZ = 22_050
        private const val BYTES_PER_SAMPLE = 2 // 16-bit mono
        /** Inter-sentence gap so narration doesn't run together. */
        private const val SENTENCE_GAP_MS = 280

        // On-disk voice-bundle artifact names — a filesystem contract shared with
        // the voice downloader and ChapterRenderJob across all three engines.
        private const val MODEL_FILE = "model.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val VOICES_BIN_FILE = "voices.bin"
        private const val ERR_LOAD_NULL = "Error: load returned null"
    }
}
