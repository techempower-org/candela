package `in`.jphe.storyvox.feature.voicelibrary

import `in`.jphe.storyvox.playback.voice.EngineKey
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import `in`.jphe.storyvox.playback.voice.VoiceEngineFamily
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptor
import `in`.jphe.storyvox.playback.voice.VoiceFamilyDescriptors
import `in`.jphe.storyvox.playback.voice.VoiceFamilyIds
import `in`.jphe.storyvox.playback.voice.VoicePresentations
import `in`.jphe.storyvox.playback.voice.toEngineKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1500 — a de-sealed (`@VoicePlugin`-only) engine's voices render in the
 * Voice Library purely from its [VoiceFamilyDescriptor]: grouped, ordered,
 * labelled and searchable with no `when (engine)` edit in this module.
 */
class PluginEngineLibraryTest {

    private val whisper = VoiceFamilyDescriptor(
        id = "voice_whisper",
        displayName = "Whisper TTS",
        description = "test",
        sourceUrl = "https://example.com",
        license = "MIT",
        sizeHint = "0 MB",
        engineFamily = VoiceEngineFamily.Local,
    )

    private val presentations = VoicePresentations(
        listOf(
            VoiceFamilyDescriptors.PIPER,
            VoiceFamilyDescriptors.SUPERTONIC,
            VoiceFamilyDescriptors.AZURE,
            whisper,
        ),
    )

    private fun voice(id: String, key: EngineKey, tier: QualityLevel = QualityLevel.High) = UiVoiceInfo(
        id = id,
        displayName = id,
        language = "en_US",
        sizeBytes = 0L,
        isInstalled = true,
        qualityLevel = tier,
        engineKey = key,
    )

    @Test fun `plugin voices group between local built-ins and cloud`() {
        val grouped = listOf(
            voice("az", EngineType.Azure("en-US-Ava", "eastus").toEngineKey()),
            voice("w1", EngineKey("voice_whisper", 0)),
            voice("st", EngineKey(VoiceFamilyIds.SUPERTONIC, 1)),
            voice("p1", EngineKey(VoiceFamilyIds.PIPER), QualityLevel.Medium),
        ).groupByEngineThenTier(presentations)
        assertEquals(
            listOf(VoiceFamilyIds.PIPER, VoiceFamilyIds.SUPERTONIC, "voice_whisper", VoiceFamilyIds.AZURE),
            grouped.keys.toList(),
        )
        assertEquals(listOf("w1"), grouped.getValue("voice_whisper").getValue(QualityLevel.High).map { it.id })
    }

    @Test fun `plugin voice subtitle and search use the descriptor`() {
        val v = voice("w1", EngineKey("voice_whisper", 0))
        assertTrue(voiceSubtitle(v, presentations).startsWith("Whisper TTS"))
        assertTrue(v.matchesQuery("whisper", presentations))
    }

    @Test fun `unregistered plugin voice still groups with neutral defaults`() {
        val grouped = listOf(voice("g", EngineKey("voice_ghost"))).groupByEngineThenTier()
        assertEquals(listOf("voice_ghost"), grouped.keys.toList())
    }
}
