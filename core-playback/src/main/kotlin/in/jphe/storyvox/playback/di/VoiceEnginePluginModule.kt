package `in`.jphe.storyvox.playback.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.engines.AzureEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.KittenEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.KokoroEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.PiperEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.SupertonicEnginePlugin
import `in`.jphe.storyvox.playback.voice.engines.SystemTtsEnginePlugin

/**
 * Issue #1372 — binds every [VoiceEnginePlugin] into the
 * `Map<String, VoiceEnginePlugin>` multibinding that
 * `VoiceEngineRegistry` consumes, keyed by [VoiceEnginePlugin.engineId]
 * (a [VoiceFamilyIds] constant).
 *
 * Adding an engine = a new plugin class + one `@Binds @IntoMap
 * @StringKey(...)` line here. No `when (engineType)` arm to grow, which
 * is the whole point of the seam. Mirrors `SourcePluginModule`, except
 * the `@SourcePlugin` set is KSP-generated whereas these six engines
 * are bound by hand (they're in-tree, not annotation-discovered).
 *
 * No `@Multibinds` default is needed: this module always contributes
 * six entries, so the map is never empty in any build that links
 * `:core-playback`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceEnginePluginModule {

    @Binds
    @IntoMap
    @StringKey(VoiceFamilyIds.PIPER)
    abstract fun bindPiper(impl: PiperEnginePlugin): VoiceEnginePlugin

    @Binds
    @IntoMap
    @StringKey(VoiceFamilyIds.KOKORO)
    abstract fun bindKokoro(impl: KokoroEnginePlugin): VoiceEnginePlugin

    @Binds
    @IntoMap
    @StringKey(VoiceFamilyIds.KITTEN)
    abstract fun bindKitten(impl: KittenEnginePlugin): VoiceEnginePlugin

    @Binds
    @IntoMap
    @StringKey(VoiceFamilyIds.SUPERTONIC)
    abstract fun bindSupertonic(impl: SupertonicEnginePlugin): VoiceEnginePlugin

    @Binds
    @IntoMap
    @StringKey(VoiceFamilyIds.AZURE)
    abstract fun bindAzure(impl: AzureEnginePlugin): VoiceEnginePlugin

    @Binds
    @IntoMap
    @StringKey(VoiceFamilyIds.SYSTEM_TTS)
    abstract fun bindSystemTts(impl: SystemTtsEnginePlugin): VoiceEnginePlugin
}
