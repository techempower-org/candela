package `in`.jphe.storyvox.playback.voice.engines

import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.playback.voice.toEngineKey
import `in`.jphe.storyvox.testkit.voice.VoiceEnginePluginContractTest

/**
 * epic/plugin-dx B3 — the six in-tree engines against the shared
 * [VoiceEnginePluginContractTest] (metadata + coherence; JVM-safe by
 * design — see the kit kdoc). Local-model plugins get inert
 * `dagger.Lazy` deps: the kit never builds specs, loads models or
 * acquires pools.
 */
private fun <T> unused(): dagger.Lazy<T> = dagger.Lazy<T> { error("not used by the contract kit") }

class PiperEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = PiperEnginePlugin(unused(), unused())
    override fun sampleKeys(): List<EngineKey> = listOf(EngineType.Piper.toEngineKey())
}

class KokoroEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = KokoroEnginePlugin(unused(), unused())
    override fun sampleKeys(): List<EngineKey> = listOf(
        EngineType.Kokoro(0).toEngineKey(),
        EngineType.Kokoro(3).toEngineKey(),
    )
}

class KittenEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = KittenEnginePlugin(unused(), unused())
    override fun sampleKeys(): List<EngineKey> = listOf(
        EngineType.Kitten(0).toEngineKey(),
        EngineType.Kitten(1).toEngineKey(),
    )
}

class SupertonicEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = SupertonicEnginePlugin(unused(), unused())
    override fun sampleKeys(): List<EngineKey> = listOf(EngineType.Supertonic(0).toEngineKey())
}

class AzureEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = AzureEnginePlugin()
    override fun sampleKeys(): List<EngineKey> = listOf(
        EngineType.Azure("en-US-AvaDragonHDLatestNeural", "eastus").toEngineKey(),
    )
}

class SystemTtsEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = SystemTtsEnginePlugin()
    override fun sampleKeys(): List<EngineKey> = listOf(
        EngineType.SystemTts("com.google.android.tts", "en-us-x-iol-network").toEngineKey(),
    )
}
