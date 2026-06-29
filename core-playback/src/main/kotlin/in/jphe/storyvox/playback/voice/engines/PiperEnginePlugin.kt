package `in`.jphe.storyvox.playback.voice.engines

import com.CodeBySonu.VoxSherpa.VoiceEngine
import `in`.jphe.storyvox.playback.EngineSampleRateCache
import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptors
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1372 — [VoiceEnginePlugin] for Piper, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.VoiceEngine` singleton.
 *
 * Piper is per-voice (each voice is its own ONNX, not a shared
 * multi-speaker model), so there is no active-speaker index to
 * re-assert before synth — [generateAudioPCM] ignores the [EngineType].
 */
@Singleton
class PiperEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.PIPER

    override val sampleRate: Int get() = EngineSampleRateCache.piperRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Piper

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = VoiceEngine.getInstance().generateAudioPCM(text, speed, pitch)

    override fun catalogEntries(): List<CatalogEntry> = VoiceCatalog.piperEntries()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.PIPER
}
