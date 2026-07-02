package `in`.jphe.storyvox.playback.audiobook

import com.CodeBySonu.VoxSherpa.KittenEngine
import com.CodeBySonu.VoxSherpa.KokoroEngine
import com.CodeBySonu.VoxSherpa.SupertonicEngine
import com.CodeBySonu.VoxSherpa.VoiceEngine
import `in`.jphe.storyvox.playback.cache.EngineMutex
import `in`.jphe.storyvox.playback.tts.SentenceChunker
import `in`.jphe.storyvox.playback.tts.detectLocale
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceEngineRegistry
import java.io.ByteArrayOutputStream
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
    private val engineMutex: EngineMutex,
    private val chunker: SentenceChunker,
    // #1372 — synthesis + export-capability dispatch goes through the
    // VoiceEnginePlugin registry instead of a per-engine `when` here.
    // epic/plugin-dx B1 — model loading too: the Context/VoiceManager
    // deps moved into the plugins with the load arms.
    private val voiceEngines: VoiceEngineRegistry,
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
            // Issue #1114 — Supertonic engine sample rate.
            is EngineType.Supertonic -> SupertonicEngine.getInstance().sampleRate
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
        // #1372 — the can-export decision is data-driven off the plugin's
        // supportsExport rather than a hardcoded Azure/SystemTts `when`, so
        // any future non-export engine is rejected here automatically. The
        // friendly per-engine copy is preserved by exportUnsupportedReason.
        val plugin = voiceEngines.forType(voice.engineType)
        if (plugin == null || !plugin.supportsExport) {
            throw UnsupportedVoiceException(exportUnsupportedReason(voice.engineType))
        }
        // epic/plugin-dx B1 — the load itself is plugin-owned: the plugin
        // builds its on-disk ModelSpec and performs the engine-specific
        // native load; the per-engine `when` that lived here is gone.
        val result = engineMutex.mutex.withLock {
            plugin.loadModel(plugin.modelSpec(voice.engineType, voice.id))
        }
        if (result != "Success") throw EngineLoadException("Voice failed to load: $result")
    }

    /** User-facing reason a voice can't be rendered offline. The decision
     *  is made in [loadVoice] via [VoiceEnginePlugin.supportsExport]; this
     *  only chooses the wording. */
    private fun exportUnsupportedReason(type: EngineType): String = when (type) {
        is EngineType.Azure ->
            "Azure voices need a network round-trip and can't be used for " +
                "offline audiobook export — pick a downloaded voice."
        is EngineType.SystemTts ->
            "System (device) voices can't be exported to a file — pick a " +
                "downloaded Piper, Kokoro, Kitten or Supertonic voice."
        else ->
            "This voice can't be exported offline — pick a downloaded " +
                "Piper, Kokoro, Kitten or Supertonic voice."
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

    // #1372 — synthesis routes through the VoiceEnginePlugin registry.
    // The plugin re-asserts the shared-model speaker from voice.engineType
    // before each synth (the #1263-correct pattern), which also fixes a
    // latent gap here: this path released and re-acquired engineMutex
    // per sentence, so a concurrent ChapterRenderJob could leave the
    // singleton on another speaker between sentences. Azure / System TTS
    // plugins return null, exactly as the old `when` did.
    private fun generateAudioPCM(voice: UiVoiceInfo, text: String): ByteArray? =
        voiceEngines.forType(voice.engineType)?.generateAudioPCM(voice.engineType, text, 1.0f, 1.0f)

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

        // epic/plugin-dx B1 — the on-disk artifact-name constants
        // (model.onnx / tokens.txt / voices.bin) moved to ModelSpec's
        // companion; the engine plugins build their own specs now.
    }
}
