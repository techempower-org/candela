package `in`.jphe.storyvox.playback.voice.engines

import android.content.Context
import com.CodeBySonu.VoxSherpa.VoiceEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.ModelSpec
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptors
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoiceManager
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
@Singleton
class PiperEnginePlugin @Inject constructor(
    private val voiceManager: dagger.Lazy<VoiceManager>,
    @ApplicationContext private val appContext: dagger.Lazy<Context>,
) : VoiceEnginePlugin {

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
}
