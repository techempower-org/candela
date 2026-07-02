package `in`.jphe.storyvox.playback.voice.engines

import android.content.Context
import com.CodeBySonu.VoxSherpa.KokoroEngine
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
import `in`.jphe.storyvox.playback.voice.VoicePlugin
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1372 — [VoiceEnginePlugin] for Kokoro, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.KokoroEngine` singleton.
 *
 * Kokoro is a shared multi-speaker model: one loaded model, the active
 * speaker selected by index. [generateAudioPCM] re-asserts the speaker
 * from the [EngineType.Kokoro.speakerId] before each synth (the
 * #1263-correct pattern), because the process-wide singleton may have
 * been left on another speaker by a concurrent render or playback.
 *
 * epic/plugin-dx B1 — [modelSpec]/[loadModel] fold in the old
 * `AudiobookSynthesizer`/`ChapterRenderJob` Kokoro load arm (shared dir
 * + speaker activation before the native load). `dagger.Lazy` deps as in
 * [PiperEnginePlugin].
 */
@VoicePlugin(VoiceFamilyIds.KOKORO)
@Singleton
class KokoroEnginePlugin @Inject constructor(
    private val voiceManager: dagger.Lazy<VoiceManager>,
    @ApplicationContext private val appContext: dagger.Lazy<Context>,
) : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.KOKORO

    override val sampleRate: Int get() = EngineSampleRateCache.kokoroRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Kokoro

    override fun modelSpec(type: EngineType, voiceId: String): ModelSpec {
        val dir = voiceManager.get().kokoroSharedDir()
        return ModelSpec.OnnxTokensVoices(
            File(dir, ModelSpec.MODEL_FILE),
            File(dir, ModelSpec.TOKENS_FILE),
            File(dir, ModelSpec.VOICES_BIN_FILE),
            speakerId = (type as? EngineType.Kokoro)?.speakerId,
        )
    }

    override fun loadModel(spec: ModelSpec): String {
        val s = spec as? ModelSpec.OnnxTokensVoices ?: return "wrong spec ${spec::class.simpleName}"
        s.speakerId?.let { KokoroEngine.getInstance().setActiveSpeakerId(it) }
        return KokoroEngine.getInstance()
            .loadModel(appContext.get(), s.onnx.absolutePath, s.tokens.absolutePath, s.voices.absolutePath)
            ?: ModelSpec.ERR_LOAD_NULL
    }

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? {
        val speakerId = (type as? EngineType.Kokoro)?.speakerId ?: return null
        KokoroEngine.getInstance().setActiveSpeakerId(speakerId)
        return KokoroEngine.getInstance().generateAudioPCM(text, speed, pitch)
    }

    override fun catalogEntries(): List<CatalogEntry> = VoiceCatalog.kokoroEntries()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.KOKORO
}
