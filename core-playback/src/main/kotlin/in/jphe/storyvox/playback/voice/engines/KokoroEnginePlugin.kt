package `in`.jphe.storyvox.playback.voice.engines

import com.CodeBySonu.VoxSherpa.KokoroEngine
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
 * Issue #1372 — [VoiceEnginePlugin] for Kokoro, wrapping the vendor
 * `com.CodeBySonu.VoxSherpa.KokoroEngine` singleton.
 *
 * Kokoro is a shared multi-speaker model: one loaded model, the active
 * speaker selected by index. [generateAudioPCM] re-asserts the speaker
 * from the [EngineType.Kokoro.speakerId] before each synth (the
 * #1263-correct pattern), because the process-wide singleton may have
 * been left on another speaker by a concurrent render or playback.
 */
@Singleton
class KokoroEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.KOKORO

    override val sampleRate: Int get() = EngineSampleRateCache.kokoroRate()

    override val supportsExport: Boolean = true

    override fun handles(type: EngineType): Boolean = type is EngineType.Kokoro

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
