package `in`.jphe.storyvox.playback.voice.engines

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
 * Issue #1372 — [VoiceEnginePlugin] for Azure HD cloud voices.
 *
 * Azure is fundamentally different from the in-process model engines:
 * synthesis is an async HTTPS round-trip handled by
 * `in.jphe.storyvox.source.azure.AzureVoiceEngine` inside `EnginePlayer`,
 * not a synchronous local generate. So this plugin reports the
 * non-synthesising surface:
 *
 * - [supportsExport] is `false` — export is offline-first; Azure needs
 *   the network and is rejected by the export path.
 * - [generateAudioPCM] returns `null` — there is no synchronous PCM
 *   path; the streaming network synth in `EnginePlayer` is out of scope
 *   for this contract (see [VoiceEnginePlugin]).
 * - [catalogEntries] is empty — Azure voices are discovered at runtime
 *   from the live region roster (`VoiceCatalog.azureEntriesFromRoster`),
 *   not from the static catalog.
 * - [sampleRate] is a nominal default; the authoritative rate comes
 *   from the live `AzureVoiceEngine` instance, which this plugin
 *   deliberately does not inject (keeping every plugin dependency-free
 *   sidesteps the Dagger-cycle risk the source/settings seam has hit
 *   before).
 */
@VoicePlugin(VoiceFamilyIds.AZURE)
@Singleton
class AzureEnginePlugin @Inject constructor() : VoiceEnginePlugin {

    override val engineId: String = VoiceFamilyIds.AZURE

    /** Nominal — Azure Neural / HD voices stream at 24 kHz by default.
     *  The real per-request rate is owned by the live AzureVoiceEngine
     *  in EnginePlayer; nothing consumes this plugin's value yet. */
    override val sampleRate: Int = NOMINAL_AZURE_SAMPLE_RATE

    override val supportsExport: Boolean = false

    override fun handles(type: EngineType): Boolean = type is EngineType.Azure

    override fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float,
        pitch: Float,
    ): ByteArray? = null

    override fun catalogEntries(): List<CatalogEntry> = emptyList()

    override fun familyDescriptor(): VoiceFamilyDescriptor = VoiceFamilyDescriptors.AZURE

    private companion object {
        const val NOMINAL_AZURE_SAMPLE_RATE = 24_000
    }
}
