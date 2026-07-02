package `in`.jphe.storyvox.playback.voice

/**
 * Issue #1372 — plugin contract for a TTS voice engine.
 *
 * Adding a new TTS engine used to mean touching ~10 files, each with a
 * `when (engineType)` arm that grew a new branch
 * (`EnginePlayer`, `AudiobookSynthesizer`, `ChapterRenderJob`,
 * `EngineSampleRateCache`, `VoiceManager`, `VoiceCatalog`,
 * `VoiceFamilyRegistry`, …). This interface inverts that: each engine
 * owns its own behaviour (which [EngineType] it [handles], its
 * [sampleRate], whether it [supportsExport], how it synthesises, the
 * voices it contributes to the catalog, and its family card) so a new
 * engine becomes a self-contained class plus one Hilt binding rather
 * than a sweep of edits.
 *
 * Implementations are wired into [VoiceEngineRegistry] via a Hilt
 * `@IntoMap` multibinding keyed by [engineId] (mirrors
 * `SourcePluginRegistry`'s `@SourcePlugin` seam). Call sites resolve a
 * plugin with [VoiceEngineRegistry.forType] / [VoiceEngineRegistry.byId]
 * and dispatch through it instead of re-deriving the `when`.
 *
 * ## Naming
 *
 * Deliberately **not** named `VoiceEngine`: three classes already own
 * that shape — the vendor `com.CodeBySonu.VoxSherpa.VoiceEngine` (the
 * Piper engine), `in.jphe.storyvox.source.azure.AzureVoiceEngine`, and
 * [`in`.jphe.storyvox.playback.tts.SystemTtsEngine]. The `…Plugin`
 * suffix reuses the codebase's existing plugin-seam vocabulary
 * (`SourcePlugin`, `SourcePluginRegistry`, `SourcePluginDescriptor`)
 * and sidesteps all three collisions.
 *
 * ## Model loading (epic/plugin-dx B1)
 *
 * The divergent per-engine load signatures (Piper: onnx + tokens;
 * Kokoro/Kitten: onnx + tokens + voices.bin; Supertonic: a shared dir)
 * are expressed as data via [ModelSpec]: [modelSpec] builds the on-disk
 * description (the local-model plugins inject `VoiceManager` +
 * `@ApplicationContext` for that), [loadModel] performs the engine's
 * native load. The old load `when` arms in `AudiobookSynthesizer` /
 * `ChapterRenderJob` are gone — those call sites dispatch through the
 * registry.
 *
 * ## What this contract intentionally does NOT cover (yet)
 *
 * - **Streaming / secondary engines** — `EnginePlayer`'s parallel-synth
 *   streaming path (`secondaryKokoroEngines`, `EngineStreamingSource`,
 *   per-engine handles) is a separate architecture, not a simple
 *   `generateAudioPCM`. It keeps its own dispatch.
 * - **A `suspend synthesize`** — synthesis must be serialised through
 *   `EngineMutex` and sequenced after a `loadModel`; a context-free
 *   suspend entry point would bypass that and corrupt the shared
 *   singletons. Callers already guard [generateAudioPCM] with
 *   `EngineMutex.withLock`, so the plugin exposes only that synchronous
 *   surface.
 */
interface VoiceEnginePlugin {

    /**
     * Stable engine key. Equals the owning family id in
     * [VoiceFamilyIds] (engine ⇄ family is 1:1 today), and is the
     * `@StringKey` this plugin is bound under in [VoiceEngineRegistry].
     */
    val engineId: String

    /**
     * PCM sample rate (Hz) this engine outputs.
     *
     * For the in-process model engines this reads the lock-free
     * [`in`.jphe.storyvox.playback.EngineSampleRateCache] (the #582
     * ANR-safe path) rather than touching the native engine monitor.
     * Cloud / system engines report a nominal default — their real
     * rate comes from the live engine instance in `EnginePlayer`, which
     * this contract does not yet own.
     */
    val sampleRate: Int

    /**
     * Whether this engine can render to a file for offline audiobook
     * export. The in-process model engines (Piper / Kokoro / Kitten /
     * Supertonic) are `true`; cloud (Azure) and framework (System TTS)
     * engines are `false` and are rejected by the export path.
     */
    val supportsExport: Boolean

    /** True when this plugin owns [type]. Keeps the `is EngineType.X`
     *  discrimination inside the engine instead of a central `when`. */
    fun handles(type: EngineType): Boolean

    /** Build the on-disk spec for [type] (voice dirs come from the injected
     *  VoiceManager). Engines without local models keep [ModelSpec.None]. */
    fun modelSpec(type: EngineType, voiceId: String): ModelSpec = ModelSpec.None

    /** Load the engine's model per [spec]; "Success" or an error string (legacy
     *  contract of the vendor engines). Callers hold `EngineMutex.mutex` across
     *  loadModel + [generateAudioPCM]. Engines with nothing to load locally
     *  (cloud / framework) keep the "Success" default. */
    fun loadModel(spec: ModelSpec): String = "Success"

    /**
     * Synthesise [text] to one 16-bit-mono-LE PCM buffer at
     * [sampleRate], or `null` when this engine can't render synchronously
     * (Azure / System TTS). [type] carries the per-voice selection
     * (e.g. the Kokoro/Kitten/Supertonic `speakerId`): shared-model
     * engines re-assert their active speaker from it before synth, the
     * #1263-correct behaviour, since the singleton may have been left on
     * another speaker by a concurrent render/playback.
     *
     * Callers MUST hold `EngineMutex.mutex` across [type]-matched
     * loadModel + this call (see `AudiobookSynthesizer` / `ChapterRenderJob`).
     */
    fun generateAudioPCM(
        type: EngineType,
        text: String,
        speed: Float = 1f,
        pitch: Float = 1f,
    ): ByteArray?

    /**
     * Static catalog voices this engine contributes — the in-process
     * model engines return their bundled roster; engines whose voices
     * are discovered at runtime from a live roster (Azure, System TTS)
     * return an empty list here and project rows through their roster
     * path instead. The union across all plugins reproduces
     * [VoiceCatalog.voices].
     */
    fun catalogEntries(): List<CatalogEntry>

    /** The Plugin Manager family card for this engine. */
    fun familyDescriptor(): VoiceFamilyDescriptor
}
