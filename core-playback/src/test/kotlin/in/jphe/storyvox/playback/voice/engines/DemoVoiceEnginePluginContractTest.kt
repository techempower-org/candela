package `in`.jphe.storyvox.playback.voice.engines

import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.VoiceEnginePlugin
import `in`.jphe.storyvox.testkit.voice.VoiceEnginePluginContractTest

/**
 * Demo Voice against the shared voice-engine contract kit. Green from
 * the first run (the stub's metadata is coherent); KEEP it green as you
 * fill the plugin in. CI's Build APK proves the @VoicePlugin Hilt
 * binding; on-device QA proves audio.
 */
class DemoVoiceEnginePluginContractTest : VoiceEnginePluginContractTest() {
    override fun plugin(): VoiceEnginePlugin = DemoVoiceEnginePlugin()
    override fun sampleKeys(): List<EngineKey> = listOf(EngineKey("voice_demovoice"))
}
