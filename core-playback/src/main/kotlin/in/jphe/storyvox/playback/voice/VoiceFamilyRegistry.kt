package `in`.jphe.storyvox.playback.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plugin-seam Phase 4 (#501) — known voice families, surfaced as cards
 * in the Plugin Manager's Voice bundles section.
 *
 * A "voice family" is a TTS engine + its bundle of voices. storyvox
 * ships four installed families (Piper, Kokoro, KittenTTS, Azure HD)
 * and reserves the "VoxSherpa upstreams" id as a placeholder for
 * future engine-lib additions.
 *
 * The family ids here are the **registry keys** — they're persisted in
 * `UiSettings.voiceFamiliesEnabled` and surfaced by [VoiceFamilyRegistry].
 * They are **not** voice ids — those are per-voice (`piper_lessac_*`,
 * `kokoro_alloy_*`, etc.) and live in [VoiceCatalog].
 *
 * @property id Stable key for `voiceFamiliesEnabled` and routing.
 * @property displayName Card title (e.g. "Piper", "Azure HD voices").
 * @property description One-line subtitle for the card.
 * @property sourceUrl Canonical upstream URL for the details modal.
 * @property license Human-readable license string for the details modal.
 * @property sizeHint Human-readable size info (e.g. "~14–30 MB each",
 *  "~330 MB single download"). Empty when not meaningful.
 * @property requiresConfiguration True for BYOK families (Azure) where
 *  the family is "installed" but unusable until the user provides
 *  credentials. The card surfaces a "Configure → Settings" CTA in
 *  place of the "Manage voices →" link.
 * @property isPlaceholder True for the "VoxSherpa upstreams" entry that
 *  represents future engine-lib voice families. Renders with a muted
 *  outline and no toggle / Manage voices link.
 * @property defaultEnabled Whether a fresh install seeds this family's
 *  toggle as ON.
 * @property engineFamily Categorisation chip shown on the card —
 *  "Local" (on-device synth) or "Cloud" (network-backed).
 */
data class VoiceFamilyDescriptor(
    val id: String,
    val displayName: String,
    val description: String,
    val sourceUrl: String,
    val license: String,
    val sizeHint: String,
    val requiresConfiguration: Boolean = false,
    val isPlaceholder: Boolean = false,
    val defaultEnabled: Boolean = true,
    val engineFamily: VoiceEngineFamily = VoiceEngineFamily.Local,
    /** #1500 — how the Voice Library renders this family's section header
     *  and rows. Defaulted from the fields above, so a new engine's
     *  descriptor gets a sensible presentation without touching any UI
     *  `when`; the built-ins override it with their historical copy. */
    val presentation: VoiceFamilyPresentation =
        VoiceFamilyPresentation.defaultFor(id, displayName, engineFamily),
)

/** Coarse engine classification for the family card's capability chip. */
enum class VoiceEngineFamily { Local, Cloud }

/**
 * #1500 — descriptor-driven Voice Library presentation. Replaces the
 * six-arm `when (engineType)` label / colour / icon / order / filter maps
 * that lived in `feature/voicelibrary`, so a de-sealed engine's rows
 * render with zero UI edits.
 *
 * Colour and icon are TOKENS, not Compose values: `:core-playback` has no
 * Compose dependency, and the feature layer owns the theme mapping. A new
 * engine picks an existing token (or keeps the neutral defaults).
 *
 * @property shortLabel Row subtitle engine segment ("Kitten · Low · Female").
 * @property sectionLabel Engine sub-header label ("Kitten (Lite)").
 * @property searchTerm Lower-case term the library search matches against.
 * @property displayOrder Outer iteration order of engine sections (ascending).
 * @property tierOrder Quality-tier order within the engine section.
 * @property accent Avatar colour token.
 * @property icon Sub-header icon token.
 * @property collapseToken Persisted collapse-store segment — `"installed:<token>"`.
 *  The built-ins keep their pre-#1500 enum names so saved collapse state
 *  survives; new engines default to their family id. NEVER change an
 *  existing value (it is an on-disk key).
 */
data class VoiceFamilyPresentation(
    val shortLabel: String,
    val sectionLabel: String = shortLabel,
    val searchTerm: String = shortLabel.lowercase(),
    val displayOrder: Int = ORDER_DEFAULT_LOCAL,
    val tierOrder: List<QualityLevel> = TIERS_BEST_FIRST,
    val accent: VoiceAccent = VoiceAccent.Neutral,
    val icon: VoiceIcon = VoiceIcon.Generic,
    val collapseToken: String,
) {
    companion object {
        /** New local engines sort after the built-in local families and
         *  before cloud (users should reach for a free local voice first). */
        const val ORDER_DEFAULT_LOCAL = 500
        const val ORDER_DEFAULT_CLOUD = 900

        val TIERS_BEST_FIRST: List<QualityLevel> = listOf(
            QualityLevel.Studio,
            QualityLevel.High,
            QualityLevel.Medium,
            QualityLevel.Low,
        )

        fun defaultFor(id: String, displayName: String, family: VoiceEngineFamily) =
            VoiceFamilyPresentation(
                shortLabel = displayName,
                displayOrder = if (family == VoiceEngineFamily.Cloud) ORDER_DEFAULT_CLOUD else ORDER_DEFAULT_LOCAL,
                icon = if (family == VoiceEngineFamily.Cloud) VoiceIcon.Cloud else VoiceIcon.Generic,
                collapseToken = id,
            )
    }
}

/** Avatar colour tokens — mapped onto the Material colour scheme by the
 *  feature layer. [Neutral] is the default for engines that don't pick one. */
enum class VoiceAccent { Primary, Secondary, Tertiary, InversePrimary, PrimaryContainer, Muted, Neutral }

/** Engine sub-header icon tokens — mapped onto Material icons by the feature
 *  layer. [Generic] is the default for engines that don't pick one. */
enum class VoiceIcon { SystemVoice, Music, Mic, Pets, Waveform, Cloud, Generic }

/**
 * #1500 — presentation lookup by engine/family id. Built from a descriptor
 * list (the Voice Library passes [VoiceFamilyRegistry.presentations], which
 * includes registered plugin engines); an id it doesn't know renders with
 * [VoiceFamilyPresentation.defaultFor] rather than failing, so a row whose
 * family card hasn't loaded still shows.
 */
class VoicePresentations(descriptors: Collection<VoiceFamilyDescriptor>) {
    private val byId: Map<String, VoiceFamilyPresentation> =
        descriptors.associate { it.id to it.presentation }

    fun forId(engineId: String): VoiceFamilyPresentation =
        byId[engineId] ?: VoiceFamilyPresentation.defaultFor(engineId, engineId, VoiceEngineFamily.Local)

    fun forVoice(voice: UiVoiceInfo): VoiceFamilyPresentation = forId(voice.engineKey.engineId)

    override fun equals(other: Any?): Boolean = other is VoicePresentations && other.byId == byId
    override fun hashCode(): Int = byId.hashCode()

    companion object {
        /** The in-tree families only — the default for pure helpers and tests. */
        val BUILT_IN: VoicePresentations = VoicePresentations(
            listOf(
                VoiceFamilyDescriptors.SYSTEM_TTS,
                VoiceFamilyDescriptors.PIPER,
                VoiceFamilyDescriptors.KOKORO,
                VoiceFamilyDescriptors.KITTEN,
                VoiceFamilyDescriptors.SUPERTONIC,
                VoiceFamilyDescriptors.AZURE,
            ),
        )
    }
}

/**
 * Canonical voice-family ids — also the keys in
 * `UiSettings.voiceFamiliesEnabled`. Kept as constants so consumers
 * (Voice Library filter, Plugin Manager card row, settings codec)
 * don't drift on string literals.
 */
object VoiceFamilyIds {
    const val PIPER = "voice_piper"
    const val KOKORO = "voice_kokoro"
    const val KITTEN = "voice_kitten"
    /** Issue #1114 — Supertonic 3 voice family. */
    const val SUPERTONIC = "voice_supertonic"
    const val AZURE = "voice_azure"
    /** Issue #676 — Android System TTS family. Zero-download
     *  first-launch tier; surfaces whatever TTS engines the OS already
     *  has installed (Google, Samsung, eSpeak, etc.). */
    const val SYSTEM_TTS = "voice_system_tts"
    /** Placeholder for future engine-lib voice families. Has no
     *  toggle in the manager card — exists so users can see the
     *  shape of "the next thing that lands here". */
    const val VOXSHERPA_UPSTREAMS = "voice_voxsherpa_upstreams"
}

/**
 * Plugin-seam Phase 4 (#501) — runtime registry of every voice family
 * the Plugin Manager surfaces as a brass-edged card.
 *
 * The list is **static** today: the four installed engines plus the
 * VoxSherpa-upstreams placeholder. Each [VoiceFamilyDescriptor] is
 * declarative metadata — the engine code itself (`KokoroEngine`,
 * `KittenEngine`, `AzureVoiceEngine`) is wired through the playback
 * pipeline independently of this registry.
 *
 * Adding a new family is a two-line change here. When a `:source-foo`
 * module lands and wants to surface a new family card without touching
 * this file, the natural next step is to migrate the list to a Hilt
 * multibinding (`Set<VoiceFamilyDescriptor>`), mirroring
 * `SourcePluginRegistry`. The static list is the cheaper Phase-4 form
 * for the four in-tree families.
 *
 * The `voiceFamily` extension on [EngineType] (in this file's
 * companion code) maps a per-voice [EngineType] to the matching family
 * id, so the Voice Library can filter voices by enabled family in O(N)
 * without touching the registry itself.
 */
@Singleton
class VoiceFamilyRegistry @Inject constructor(
    /** #1500 — registered engines, so a de-sealed `@VoicePlugin` engine's
     *  family card appears without editing the curated list below. Lazy:
     *  resolving the plugin map is deferred to first [descriptors] read.
     *  Defaulted to "no plugins" so JVM tests keep `VoiceFamilyRegistry()`. */
    private val engineRegistry: dagger.Lazy<VoiceEngineRegistry> = NO_ENGINES,
) {

    /** All known voice families, in display order. System TTS comes
     *  first as the zero-download first-launch tier (#676); then the
     *  in-process neural families (Piper / Kokoro / Kitten); then
     *  cloud (Azure); then placeholders.
     *
     *  Supertonic sits after Kitten now that the engine has shipped
     *  (#1236 flipped [VoiceCatalog.SUPERTONIC_ENABLED] to true), so
     *  [listOfNotNull] keeps it and a shipped build shows seven
     *  descriptors. The flag stays as the single re-gate point. */
    // #1372 — the literals now live in [VoiceFamilyDescriptors] so the
    // family cards and each `VoiceEnginePlugin.familyDescriptor()` share
    // one source of truth. This list keeps the curated display order
    // (System TTS first as the zero-download tier; then the in-process
    // neural families; then cloud; then the placeholder) and the
    // [VoiceCatalog.SUPERTONIC_ENABLED] gate, so the rendered output is
    // unchanged. listOfNotNull drops Supertonic if the flag is ever
    // flipped back, re-gating the card and the voices together.
    //
    // #1500 — registered engines that are NOT one of the curated built-ins
    // (i.e. de-sealed `@VoicePlugin` engines) are appended after the
    // curated engines, in engineId order, ahead of the placeholder.
    val descriptors: List<VoiceFamilyDescriptor> by lazy {
        val curated = listOfNotNull(
            VoiceFamilyDescriptors.SYSTEM_TTS,
            VoiceFamilyDescriptors.PIPER,
            VoiceFamilyDescriptors.KOKORO,
            VoiceFamilyDescriptors.KITTEN,
            if (VoiceCatalog.SUPERTONIC_ENABLED) VoiceFamilyDescriptors.SUPERTONIC else null,
            VoiceFamilyDescriptors.AZURE,
        )
        val plugins = engineRegistry.get().all()
            .filter { it.engineId !in BUILT_IN_ENGINE_IDS }
            .sortedBy { it.engineId }
            .map { it.familyDescriptor() }
        curated + plugins + VoiceFamilyDescriptors.VOXSHERPA_PLACEHOLDER
    }

    /** #1500 — Voice Library presentation lookup over every family here. */
    val presentations: VoicePresentations by lazy { VoicePresentations(descriptors) }

    /** Lookup by stable family id. */
    fun byId(id: String): VoiceFamilyDescriptor? = descriptors.firstOrNull { it.id == id }

    /** All non-placeholder family ids — the set that participates in
     *  Voice Library filtering. The placeholder has no voices and is
     *  never toggled. */
    val toggleableIds: List<String> by lazy { descriptors.filterNot { it.isPlaceholder }.map { it.id } }

    private companion object {
        val NO_ENGINES: dagger.Lazy<VoiceEngineRegistry> = dagger.Lazy { VoiceEngineRegistry(emptyMap()) }
    }
}

/**
 * Map a per-voice [EngineType] to the [VoiceFamilyDescriptor.id] that
 * owns it. Used by the Voice Library filter to hide voices belonging
 * to a disabled family.
 *
 * Unknown engine types fall back to [VoiceFamilyIds.VOXSHERPA_UPSTREAMS]
 * — they're never enabled, so unrecognised engines surface as filtered
 * out by default. This keeps a future `EngineType.Foo` from leaking
 * into the Voice Library before its family card lands.
 */
fun EngineType.voiceFamilyId(): String = when (this) {
    is EngineType.Piper -> VoiceFamilyIds.PIPER
    is EngineType.Kokoro -> VoiceFamilyIds.KOKORO
    is EngineType.Kitten -> VoiceFamilyIds.KITTEN
    is EngineType.Supertonic -> VoiceFamilyIds.SUPERTONIC
    is EngineType.Azure -> VoiceFamilyIds.AZURE
    is EngineType.SystemTts -> VoiceFamilyIds.SYSTEM_TTS
    // #1500 — a de-sealed engine's family id IS its engine id.
    is EngineType.Plugin -> key.engineId
}
