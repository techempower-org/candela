package `in`.jphe.storyvox.playback.voice.engines

import `in`.jphe.storyvox.playback.tts.SystemTtsEngine
import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptors
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoicePlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1372 / #676 — [VoiceEnginePlugin] for Android System TTS.
 *
 * System TTS routes through the OS `TextToSpeech` framework via an
 * instance-based `in.jphe.storyvox.playback.tts.SystemTtsEngine` (bound
 * per-engine-package, async `onInit`), not a process-wide singleton. So,
 * like Azure, this plugin reports the non-synthesising surface:
 *
 * - [supportsExport] is `false` — a framework binder can't render to a
 *   file in the export path; rejected there.
 * - [generateAudioPCM] returns `null` — synthesis runs through the live
 *   `SystemTtsEngine.generateAudioPCMBlocking` in `EnginePlayer`, which
 *   this contract does not yet own.
 * - [catalogEntries] is empty — System TTS voices are discovered at
 *   runtime from the device roster
 *   (`VoiceCatalog.systemTtsEntriesFromRoster`).
 * - [sampleRate] reports `SystemTtsEngine.DEFAULT_SAMPLE_RATE`; the real
 *   rate comes from the live engine instance per chosen OS voice.
 */
@VoicePlugin(VoiceFamilyIds.SYSTEM_TTS)
@Singleton
class SystemTtsEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.SYSTEM_TTS

    override val sampleRate: Int = SystemTtsEngine.DEFAULT_SAMPLE_RATE

    override val supportsExport: Boolean = false

    override fun handles(type: EngineType): Boolean = type is EngineType.SystemTts

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = null

    override fun catalogEntries(): List<CatalogEntry> = emptyList()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.SYSTEM_TTS
}
