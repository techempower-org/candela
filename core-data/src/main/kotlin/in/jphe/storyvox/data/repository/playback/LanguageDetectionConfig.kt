package `in`.jphe.storyvox.data.repository.playback

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1233 — auto-detect a sentence's language and switch the TTS
 * voice to one that matches it.
 *
 * Sibling of [VoiceTuningConfig] / [PlaybackBufferConfig] / [ParallelSynthConfig]:
 * surfaced as its own narrow contract so `core-playback` (whose
 * `EnginePlayer` is the only consumer) stays free of feature-layer
 * types. The implementation lives in `:app`'s `SettingsRepositoryUiImpl`,
 * which already implements the other playback configs against a single
 * DataStore.
 *
 * **Off by default.** When `false` (the default), playback is
 * byte-identical to pre-#1233 behaviour — the detector never runs, the
 * synth path is unchanged, and the on-disk PCM cache is keyed exactly as
 * before. Flipping it `true` opts the listener into per-sentence
 * language routing.
 *
 * **Scope of effect.** Routing currently acts only on the **Kokoro**
 * engine family, the one in-app family with multi-language speakers
 * (en/es/fr/hi/it/ja/pt/zh — see [`in`.jphe.storyvox.playback.voice.VoiceCatalog]).
 * Kokoro shares one model across all 53 speakers, so switching to a
 * target-language speaker is a reload-free `setActiveSpeakerId` call.
 * For every other engine (Piper, Kitten, Azure, System TTS) the router
 * finds no same-family target-language voice and the listener stays on
 * their chosen voice — the graceful fallback required by #1233.
 */
interface LanguageDetectionConfig {

    /**
     * Live flow of the "auto-detect language & switch voice" preference.
     * Default `false` (disabled).
     */
    val autoLanguageDetectionEnabled: Flow<Boolean>

    /**
     * Snapshot read for callers that need a single value without
     * subscribing. Implementations return the most recent value, falling
     * back to the documented default (`false`) if the underlying store
     * hasn't emitted yet.
     */
    suspend fun currentAutoLanguageDetectionEnabled(): Boolean
}
