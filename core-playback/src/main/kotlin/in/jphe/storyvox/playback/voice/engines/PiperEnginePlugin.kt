package `in`.jphe.storyvox.playback.voice.engines

import android.content.Context
import com.CodeBySonu.VoxSherpa.VoiceEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_EXPRESSIVE
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_STEADY
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_W_EXPRESSIVE
import `in`.jphe.storyvox.data.repository.playback.NOISE_SCALE_W_STEADY
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.ModelSpec
import `in`.jphe.storyvox.playback.voice.StreamingSynth
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
 * Issue #1372 — [VoiceEnginePlugin] for Piper, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.VoiceEngine` singleton.
 *
 * Piper is per-voice (each voice is its own ONNX, not a shared
 * multi-speaker model), so there is no active-speaker index to
 * re-assert before synth — [generateAudioPCM] ignores the [EngineType].
 *
 * epic/plugin-dx B1 — [modelSpec]/[loadModel] fold in the old
 * `AudiobookSynthesizer`/`ChapterRenderJob` Piper load arm. The deps are
 * `dagger.Lazy` deliberately: only those two methods touch them, the
 * eagerly-built registry map stays construction-light, and the existing
 * registry unit tests keep constructing the plugin with inert fakes.
 */
@VoicePlugin(VoiceFamilyIds.PIPER)
@Singleton
class PiperEnginePlugin @Inject constructor(
    private val voiceManager: dagger.Lazy<VoiceManager>,
    @ApplicationContext private val appContext: dagger.Lazy<Context>,
) : VoiceEnginePlugin, StreamingSynth {

    override val engineId: String = VoiceFamilyIds.PIPER

    override val sampleRate: Int get() = EngineSampleRateCache.piperRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Piper

    override fun modelSpec(type: EngineType, voiceId: String): ModelSpec {
        val dir = voiceManager.get().voiceDirFor(voiceId)
        return ModelSpec.OnnxWithTokens(
            File(dir, ModelSpec.MODEL_FILE),
            File(dir, ModelSpec.TOKENS_FILE),
        )
    }

    override fun loadModel(spec: ModelSpec): String {
        val s = spec as? ModelSpec.OnnxWithTokens ?: return "wrong spec ${spec::class.simpleName}"
        return VoiceEngine.getInstance()
            .loadModel(appContext.get(), s.onnx.absolutePath, s.tokens.absolutePath)
            ?: ModelSpec.ERR_LOAD_NULL
    }

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = VoiceEngine.getInstance().generateAudioPCM(text, speed, pitch)

    override fun catalogEntries(): List<CatalogEntry> = VoiceCatalog.piperEntries()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.PIPER

    /** epic/plugin-dx B2 — Tier 3 (#88) secondary construction, moved
     *  verbatim from EnginePlayer's Piper arm: per-instance VoiceEngine +
     *  the primary's noiseScale pair (prosody match) + cap-on-failure. */
    override fun acquirePool(
        spec: ModelSpec,
        size: Int,
        threadsPerInstance: Int,
        tuning: StreamingTuning,
    ): List<StreamingSynth.Handle> {
        val s = spec as? ModelSpec.OnnxWithTokens ?: return emptyList()
        val handles = mutableListOf<StreamingSynth.Handle>()
        for (i in 1..size) {
            android.util.Log.i(LOG_TAG, "Tier 3 attempting secondary Piper $i")
            val secondary = VoiceEngine()
            // Propagate noiseScale settings so all instances render with
            // the same prosody as the primary (Steady vs Expressive).
            val ns = if (tuning.voiceSteady) NOISE_SCALE_STEADY else NOISE_SCALE_EXPRESSIVE
            val nsW = if (tuning.voiceSteady) NOISE_SCALE_W_STEADY else NOISE_SCALE_W_EXPRESSIVE
            secondary.setNoiseScale(ns)
            secondary.setNoiseScaleW(nsW)
            val r = secondary.loadModel(
                appContext.get(),
                s.onnx.absolutePath,
                s.tokens.absolutePath,
                threadsPerInstance,
            )
            if (r == "Success") {
                handles += PiperHandle(secondary)
                android.util.Log.i(LOG_TAG, "Tier 3 secondary Piper $i loaded ok")
            } else {
                runCatching { secondary.destroy() }
                android.util.Log.w(
                    LOG_TAG,
                    "Tier 3 secondary $i (Piper) load failed: " +
                        "$r — capping at ${handles.size + 1} instances.",
                )
                break
            }
        }
        return handles
    }

    private class PiperHandle(
        private val engine: VoiceEngine,
    ) : StreamingSynth.Handle {
        // #582 — lock-free rate; all Piper instances share one model file
        // per process, so the engine-type-scoped cache is correct.
        override val sampleRate: Int
            get() = EngineSampleRateCache.piperRate().takeIf { it > 0 } ?: DEFAULT_SAMPLE_RATE

        override fun generatePCM(text: String, speed: Float, pitch: Float): ByteArray? =
            engine.generateAudioPCM(text, speed, pitch)

        override fun destroy() {
            runCatching { engine.destroy() }
        }
    }

    private companion object {
        const val LOG_TAG = "PiperEnginePlugin"
        const val DEFAULT_SAMPLE_RATE = 22_050
    }
}
