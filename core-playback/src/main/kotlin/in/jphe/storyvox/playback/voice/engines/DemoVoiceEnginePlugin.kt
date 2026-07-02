package `in`.jphe.storyvox.playback.voice.engines

import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoicePlugin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Demo Voice voice engine (scaffolded by scripts/new-voice-engine.sh).
 *
 * Every member is an honest stub — fill them in against your engine
 * library, guided by docs/CONTRIBUTING-VOICES.md:
 *  - synth: [generateAudioPCM] (hold `EngineMutex.mutex` at call sites —
 *    the contract kdoc on [VoiceEnginePlugin] is law)
 *  - model loading: override `modelSpec`/`loadModel` ([`in`.jphe.storyvox
 *    .playback.voice.ModelSpec] describes what you need on disk)
 *  - catalog: [catalogEntries] + [familyDescriptor] (the Plugin Manager card)
 *  - parallel synth (optional): implement
 *    [`in`.jphe.storyvox.playback.voice.StreamingSynth]
 *
 * NOTE [handles] returns false: new engines are DE-SEALED — they have no
 * `EngineType` variant. Dispatch reaches this plugin via
 * `VoiceEngineRegistry.byKey(EngineKey("voice_demovoice"))`, not via the
 * legacy sealed `when`. That is the point of the epic.
 */
@VoicePlugin("voice_demovoice")
@Singleton
class DemoVoiceEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = "voice_demovoice"

    /** Nominal until your engine reports a real rate. */
    override val sampleRate: Int = 22_050

    /** Flip to true only when [generateAudioPCM] renders real PCM
     *  synchronously (offline export path). */
    override val supportsExport: Boolean = false

    /** De-sealed engines have no EngineType variant — see class kdoc. */
    override fun handles(type: EngineType): Boolean = false

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = null

    override fun catalogEntries(): List<CatalogEntry> = emptyList()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptor(
        id = "voice_demovoice",
        displayName = "Demo Voice",
        description = "One-line engine description (fill me in)",
        sourceUrl = "https://example.com",
        license = "TODO license",
        sizeHint = "~0 MB",
        defaultEnabled = false,
    )
}
