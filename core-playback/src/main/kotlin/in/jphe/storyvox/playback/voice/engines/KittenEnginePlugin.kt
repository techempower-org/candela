package `in`.jphe.storyvox.playback.voice.engines

import android.content.Context
import com.CodeBySonu.VoxSherpa.KittenEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.ModelSpec
import `in`.jphe.storyvox.playback.voice.PoolAttempt
import `in`.jphe.storyvox.playback.voice.StreamingSynth
import `in`.jphe.storyvox.playback.voice.buildCapOnFailurePool
import `in`.jphe.storyvox.playback.voice.StreamingTuning
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptors
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoiceManager
import `in`.jphe.storyvox.playback.voice.VoicePlugin
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1372 — [VoiceEnginePlugin] for KittenTTS, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.KittenEngine` singleton.
 *
 * Shared multi-speaker model (a twin of Kokoro): [generateAudioPCM]
 * re-asserts the speaker from [EngineType.Kitten.speakerId] before each
 * synth (the #1263-correct pattern).
 *
 * epic/plugin-dx B1 — [modelSpec]/[loadModel] fold in the old
 * `AudiobookSynthesizer`/`ChapterRenderJob` Kitten load arm (shared dir
 * + speaker activation before the native load). `dagger.Lazy` deps as in
 * [PiperEnginePlugin].
 */
@VoicePlugin(VoiceFamilyIds.KITTEN)
@Singleton
class KittenEnginePlugin @Inject constructor(
    private val voiceManager: dagger.Lazy<VoiceManager>,
    @ApplicationContext private val appContext: dagger.Lazy<Context>,
) : VoiceEnginePlugin, StreamingSynth {

    override val engineId: String = VoiceFamilyIds.KITTEN

    override val sampleRate: Int get() = EngineSampleRateCache.kittenRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Kitten

    override fun modelSpec(type: EngineType, voiceId: String): ModelSpec {
        val dir = voiceManager.get().kittenSharedDir()
        return ModelSpec.OnnxTokensVoices(
            File(dir, ModelSpec.MODEL_FILE),
            File(dir, ModelSpec.TOKENS_FILE),
            File(dir, ModelSpec.VOICES_BIN_FILE),
            speakerId = (type as? EngineType.Kitten)?.speakerId,
        )
    }

    override fun loadModel(spec: ModelSpec): String {
        val s = spec as? ModelSpec.OnnxTokensVoices ?: return "wrong spec ${spec::class.simpleName}"
        s.speakerId?.let { KittenEngine.getInstance().setActiveSpeakerId(it) }
        return KittenEngine.getInstance()
            .loadModel(appContext.get(), s.onnx.absolutePath, s.tokens.absolutePath, s.voices.absolutePath)
            ?: ModelSpec.ERR_LOAD_NULL
    }

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? {
        val speakerId = (type as? EngineType.Kitten)?.speakerId ?: return null
        KittenEngine.getInstance().setActiveSpeakerId(speakerId)
        return KittenEngine.getInstance().generateAudioPCM(text, speed, pitch)
    }

    override fun catalogEntries(): List<CatalogEntry> = VoiceCatalog.kittenEntries()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.KITTEN

    /** epic/plugin-dx B2 — Tier 3 (#119) secondary construction, moved
     *  verbatim from EnginePlayer's Kitten arm: per-instance KittenEngine
     *  pinned to the active speaker (spec.speakerId), cap-on-failure.
     *  Sessions are small (~60–80 MB fp16), the friendliest engine for
     *  the parallel slider on low-end hardware. */
    override fun acquirePool(
        spec: ModelSpec,
        size: Int,
        threadsPerInstance: Int,
        tuning: StreamingTuning,
    ): List<StreamingSynth.Handle> {
        val s = spec as? ModelSpec.OnnxTokensVoices ?: return emptyList()
        // #1503 — the cap-on-failure loop is shared (buildCapOnFailurePool);
        // only the Kitten-specific build/configure/load stays here in [attempt].
        return buildCapOnFailurePool(size, "Kitten", LOG_TAG) { _ ->
            val secondary = KittenEngine()
            s.speakerId?.let { secondary.setActiveSpeakerId(it) }
            val r = secondary.loadModel(
                appContext.get(),
                s.onnx.absolutePath,
                s.tokens.absolutePath,
                s.voices.absolutePath,
                threadsPerInstance,
            )
            if (r == "Success") {
                PoolAttempt.Loaded(KittenHandle(secondary))
            } else {
                runCatching { secondary.destroy() }
                PoolAttempt.Failed(r)
            }
        }
    }

    private class KittenHandle(
        private val engine: KittenEngine,
    ) : StreamingSynth.Handle {
        // #582 — Kitten is architecturally 24 kHz across every speaker.
        override val sampleRate: Int
            get() = EngineSampleRateCache.kittenRate().takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

        override fun generatePCM(text: String, speed: Float, pitch: Float): ByteArray? =
            engine.generateAudioPCM(text, speed, pitch)

        override fun destroy() {
            runCatching { engine.destroy() }
        }
    }

    private companion object {
        const val LOG_TAG = "KittenEnginePlugin"
        const val DEFAULT_SAMPLE_RATE = 22_050
    }
}
