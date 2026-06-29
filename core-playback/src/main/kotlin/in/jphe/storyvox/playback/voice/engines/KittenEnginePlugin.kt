package `in`.jphe.storyvox.playback.voice.engines

import com.CodeBySonu.VoxSherpa.KittenEngine
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
 * Issue #1372 — [VoiceEnginePlugin] for KittenTTS, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.KittenEngine` singleton.
 *
 * Shared multi-speaker model (a twin of Kokoro): [generateAudioPCM]
 * re-asserts the speaker from [EngineType.Kitten.speakerId] before each
 * synth (the #1263-correct pattern).
 */
@Singleton
class KittenEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.KITTEN

    override val sampleRate: Int get() = EngineSampleRateCache.kittenRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Kitten

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
