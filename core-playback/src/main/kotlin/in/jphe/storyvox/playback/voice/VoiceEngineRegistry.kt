package `in`.jphe.storyvox.playback.voice

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1372 — runtime registry of every [VoiceEnginePlugin].
 *
 * Singleton. Hilt injects the `Map<String, VoiceEnginePlugin>`
 * multibinding populated by the `@Binds @IntoMap @StringKey(...)`
 * factories in `VoiceEnginePluginModule` (one per engine, keyed by
 * [VoiceEnginePlugin.engineId]). Call sites that used to grow a
 * `when (engineType)` arm per engine instead ask the registry —
 * [forType] to dispatch by [EngineType], [byId] for the family key —
 * so a new engine is a new plugin class + one binding, touching no
 * existing dispatch site.
 *
 * Mirrors `SourcePluginRegistry` (the `@SourcePlugin` seam): same
 * `@Inject`-but-not-`internal` constructor so a `:core-playback` unit
 * test can build one directly from a `Map<String, VoiceEnginePlugin>`
 * of real or fake plugins without standing up Hilt.
 */
@Singleton
class VoiceEngineRegistry @Inject constructor(
    private val plugins: Map<String, @JvmSuppressWildcards VoiceEnginePlugin>,
) {

    init {
        // Fail fast at graph build if a plugin's declared engineId
        // disagrees with the @StringKey it was bound under — the map
        // key is what byId() looks up, the engineId is what plugins
        // self-report (and what forType() round-trips through), so a
        // mismatch would make byId(plugin.engineId) silently miss.
        val mismatched = plugins.entries.filter { (key, plugin) -> key != plugin.engineId }
        if (mismatched.isNotEmpty()) {
            val detail = mismatched.joinToString("; ") { (key, plugin) ->
                "bound as '$key' but engineId='${plugin.engineId}' (${plugin::class.simpleName})"
            }
            error(
                "VoiceEngineRegistry: @StringKey / engineId mismatch — $detail. " +
                    "Bind each VoiceEnginePlugin under @StringKey(<its engineId>).",
            )
        }
    }

    /** The plugin owning [type], or null if no registered engine
     *  handles it. Dispatch entry point that replaces a
     *  `when (engineType)`. */
    fun forType(type: EngineType): VoiceEnginePlugin? =
        plugins.values.firstOrNull { it.handles(type) }

    /** The plugin registered under [engineId] (a [VoiceFamilyIds]
     *  constant), or null. */
    fun byId(engineId: String): VoiceEnginePlugin? = plugins[engineId]

    /** Every registered plugin. Iteration order is unspecified (Hilt
     *  map order); sort by [VoiceEnginePlugin.engineId] at call sites
     *  that need determinism. */
    fun all(): Collection<VoiceEnginePlugin> = plugins.values

    /**
     * The union of every plugin's static [VoiceEnginePlugin.catalogEntries].
     * Reproduces the set of [VoiceCatalog.voices] (the in-process model
     * engines contribute their rosters; Azure / System TTS contribute
     * nothing static). Order is unspecified — sort if a consumer needs
     * a stable order.
     */
    fun allCatalogEntries(): List<CatalogEntry> = plugins.values.flatMap { it.catalogEntries() }

    /** Each registered engine's family descriptor. Does NOT include the
     *  non-engine "VoxSherpa upstreams" placeholder — that stays owned
     *  by [VoiceFamilyRegistry]. Order is unspecified. */
    fun allFamilyDescriptors(): List<VoiceFamilyDescriptor> =
        plugins.values.map { it.familyDescriptor() }
}
