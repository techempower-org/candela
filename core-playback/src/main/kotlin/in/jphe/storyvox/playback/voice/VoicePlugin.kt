package `in`.jphe.storyvox.playback.voice

/**
 * epic/plugin-dx B1 — marker annotation that auto-registers a
 * [VoiceEnginePlugin] implementation into [VoiceEngineRegistry].
 *
 * Decorate a `VoiceEnginePlugin` implementation with this annotation and
 * the `:core-plugin-ksp` `VoicePluginProcessor` generates the Hilt
 * `@Binds @IntoMap @StringKey(engineId)` module that contributes it to
 * the `Map<String, VoiceEnginePlugin>` multibinding the registry
 * consumes — the voice twin of `@SourcePlugin` (#384/#1371/#1481).
 * A new engine is an annotated class; there is no hand-written DI module
 * to touch (the old `VoiceEnginePluginModule` is deleted).
 *
 * [engineId] must be a compile-time constant equal to the plugin's own
 * `engineId` property — bind-key/self-report mismatches fail fast in
 * [VoiceEngineRegistry]'s init check. Use the [VoiceFamilyIds] constants.
 *
 * Retention is BINARY for the same reason as `@SourcePlugin`: visible to
 * the KSP pass, never needed via runtime reflection.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class VoicePlugin(val engineId: String)
