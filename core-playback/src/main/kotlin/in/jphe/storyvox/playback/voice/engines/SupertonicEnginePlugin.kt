package `in`.jphe.storyvox.playback.voice.engines

import com.CodeBySonu.VoxSherpa.SupertonicEngine
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
 * Issue #1372 — [VoiceEnginePlugin] for Supertonic 3, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.SupertonicEngine` singleton.
 *
 * Shared multi-speaker model (a twin of Kokoro / Kitten):
 * [generateAudioPCM] re-asserts the speaker from
 * [EngineType.Supertonic.speakerId] before each synth (the
 * #1263-correct pattern).
 *
 * [catalogEntries] honours [VoiceCatalog.SUPERTONIC_ENABLED] so the
 * union of every plugin's entries reproduces [VoiceCatalog.voices] —
 * one flag still re-gates the voices and the family card together.
 */
@Singleton
class SupertonicEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.SUPERTONIC

    override val sampleRate: Int get() = EngineSampleRateCache.supertonicRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Supertonic

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? {
        val speakerId = (type as? EngineType.Supertonic)?.speakerId ?: return null
        SupertonicEngine.getInstance().setActiveSpeakerId(speakerId)
        return SupertonicEngine.getInstance().generateAudioPCM(text, speed, pitch)
    }

    override fun catalogEntries(): List<CatalogEntry> =
        if (VoiceCatalog.SUPERTONIC_ENABLED) VoiceCatalog.supertonicEntries() else emptyList()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.SUPERTONIC
}
