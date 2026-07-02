package `in`.jphe.storyvox.playback.voice.engines

import android.content.Context
import com.CodeBySonu.VoxSherpa.KittenEngine
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
@Singleton
class KittenEnginePlugin @Inject constructor(
    private val voiceManager: dagger.Lazy<VoiceManager>,
    @ApplicationContext private val appContext: dagger.Lazy<Context>,
) : VoiceEnginePlugin {

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
}
