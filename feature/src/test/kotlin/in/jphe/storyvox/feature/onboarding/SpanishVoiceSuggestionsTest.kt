package `in`.jphe.storyvox.feature.onboarding

import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.UiVoiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1466 — the Spanish-first voice step's starter list. */
class SpanishVoiceSuggestionsTest {

    private fun system(id: String, language: String) = UiVoiceInfo(
        id = id,
        displayName = id,
        language = language,
        sizeBytes = 0L,
        isInstalled = true,
        qualityLevel = QualityLevel.Medium,
        engineType = EngineType.SystemTts(engineName = "com.google.android.tts", voiceName = id),
    )

    private fun kokoro(id: String, language: String, speaker: Int, installed: Boolean = false) = UiVoiceInfo(
        id = id,
        displayName = id,
        language = language,
        sizeBytes = 0L,
        isInstalled = installed,
        qualityLevel = QualityLevel.High,
        engineType = EngineType.Kokoro(speakerId = speaker),
    )

    private fun piper(id: String, language: String) = UiVoiceInfo(
        id = id,
        displayName = id,
        language = language,
        sizeBytes = 63_000_000L,
        isInstalled = false,
        qualityLevel = QualityLevel.Medium,
        engineType = EngineType.Piper,
    )

    private val dora = kokoro("kokoro_dora_es_ES_28", "es_ES", 28)
    private val alex = kokoro("kokoro_alex_es_ES_29", "es_ES", 29)
    private val catalog = listOf(
        piper("piper_lessac_en_US_medium", "en_US"),
        kokoro("kokoro_heart_en_US_3", "en_US", 3),
        dora,
        alex,
        kokoro("kokoro_siwis_fr_FR_30", "fr_FR", 30),
    )

    @Test
    fun `on-device Spanish voices come first then Kokoro Spanish speakers`() {
        val installed = listOf(
            system("sys_en", "en_US"),
            system("sys_es_es", "es_ES"),
            system("sys_es_us", "es_US"),
        )
        val ids = spanishVoiceSuggestions(installed, catalog).map { it.id }
        assertEquals(listOf("sys_es_us", "sys_es_es", dora.id, alex.id), ids)
    }

    @Test
    fun `no system voices still offers Dora and Alex`() {
        val ids = spanishVoiceSuggestions(emptyList(), catalog).map { it.id }
        assertEquals(listOf(dora.id, alex.id), ids)
    }

    @Test
    fun `caps on-device voices at two and ranks US and Mexico ahead of Spain`() {
        val installed = listOf(
            system("a_es", "es_ES"),
            system("b_mx", "es_MX"),
            system("c_us", "es_US"),
            system("d_us", "es_US"),
        )
        val ids = spanishVoiceSuggestions(installed, catalog).map { it.id }
        assertEquals(listOf("c_us", "d_us", dora.id, alex.id), ids)
    }

    @Test
    fun `installed Kokoro copy wins over its catalog twin`() {
        val installedDora = dora.copy(isInstalled = true)
        val result = spanishVoiceSuggestions(listOf(installedDora), catalog)
        assertTrue(result.first { it.id == dora.id }.isInstalled)
    }

    @Test
    fun `isSpanish reads both tag styles`() {
        assertTrue(isSpanish("es_MX"))
        assertTrue(isSpanish("es-US"))
        assertTrue(isSpanish("es"))
        assertFalse(isSpanish("en_US"))
        assertFalse(isSpanish("eu_ES"))
    }
}
