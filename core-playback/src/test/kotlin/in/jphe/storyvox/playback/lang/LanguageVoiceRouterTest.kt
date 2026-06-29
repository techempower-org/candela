package `in`.jphe.storyvox.playback.lang

import `in`.jphe.storyvox.playback.voice.CatalogEntry
import `in`.jphe.storyvox.playback.voice.EngineType
import `in`.jphe.storyvox.playback.voice.QualityLevel
import `in`.jphe.storyvox.playback.voice.VoiceCatalog
import `in`.jphe.storyvox.playback.voice.VoiceGender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1233 — the routing policy that maps a detected language to a
 * voice. This is the load-bearing, fully-deterministic core of the
 * feature, so it gets exercised hard against the REAL [VoiceCatalog]
 * (the Kokoro section ships the multi-language speakers the feature
 * leans on) plus a few synthetic entries for the family/gender edges.
 *
 * Pure-JVM (no Robolectric / emulator) — [LanguageVoiceRouter] is a pure
 * function over data.
 */
class LanguageVoiceRouterTest {

    private fun voice(id: String): CatalogEntry =
        VoiceCatalog.byId(id) ?: error("catalog missing $id — test fixture drift")

    // Real catalog anchors (see VoiceCatalog.kokoroEntries).
    private val kokoroHeartEnUsFemale get() = voice("kokoro_heart_en_US_3")
    private val kokoroAdamEnUsMale get() = voice("kokoro_adam_en_US_11")
    private val piperLessacEnUs get() = voice("piper_lessac_en_US_high")

    @Test
    fun `English Kokoro voice routes French text to the French Kokoro speaker`() {
        val target = LanguageVoiceRouter.route("fr", kokoroHeartEnUsFemale, VoiceCatalog.voices)
        assertEquals("kokoro_siwis_fr_FR_30", target?.id)
    }

    @Test
    fun `detected language already matching the current voice does not switch`() {
        assertNull(LanguageVoiceRouter.route("en", kokoroHeartEnUsFemale, VoiceCatalog.voices))
    }

    @Test
    fun `Piper voice has no French sibling so it stays put (graceful fallback)`() {
        // The shipped Piper catalog is English-only; #1233 requires the
        // listener simply keeps their voice when no same-family match
        // exists, rather than crossing engines into a 330 MB Kokoro pull.
        assertNull(LanguageVoiceRouter.route("fr", piperLessacEnUs, VoiceCatalog.voices))
    }

    @Test
    fun `gender continuity — female English routes Spanish to the female Spanish voice`() {
        // es_ES ships exactly one female (Dora, 28) and one male (Alex, 29)
        // Kokoro speaker, so gender preference is unambiguous.
        val target = LanguageVoiceRouter.route("es", kokoroHeartEnUsFemale, VoiceCatalog.voices)
        assertEquals("kokoro_dora_es_ES_28", target?.id)
    }

    @Test
    fun `gender continuity — male English routes Spanish to the male Spanish voice`() {
        val target = LanguageVoiceRouter.route("es", kokoroAdamEnUsMale, VoiceCatalog.voices)
        assertEquals("kokoro_alex_es_ES_29", target?.id)
    }

    @Test
    fun `gender preference never blocks a switch when only the other gender exists`() {
        // fr_FR ships only Siwis (female). A male listener should still be
        // routed to French — gender is a soft tiebreak, not a filter.
        val target = LanguageVoiceRouter.route("fr", kokoroAdamEnUsMale, VoiceCatalog.voices)
        assertEquals("kokoro_siwis_fr_FR_30", target?.id)
    }

    @Test
    fun `Japanese routing picks a Japanese Kokoro voice of the matching gender`() {
        val female = LanguageVoiceRouter.route("ja", kokoroHeartEnUsFemale, VoiceCatalog.voices)
        assertEquals("ja_JP", female?.language)
        assertEquals(VoiceGender.Female, female?.gender)
        assertTrue(female?.engineType is EngineType.Kokoro)

        // Only one male ja_JP voice (Kumo, 41) — fully deterministic.
        val male = LanguageVoiceRouter.route("ja", kokoroAdamEnUsMale, VoiceCatalog.voices)
        assertEquals("kokoro_kumo_ja_JP_41", male?.id)
    }

    @Test
    fun `BCP-47 region and script suffixes are normalised before matching`() {
        // "fr-FR" (region) and "zh-Hant" (script) must reduce to fr / zh.
        assertEquals(
            "kokoro_siwis_fr_FR_30",
            LanguageVoiceRouter.route("fr-FR", kokoroHeartEnUsFemale, VoiceCatalog.voices)?.id,
        )
        val zh = LanguageVoiceRouter.route("zh-Hant", kokoroHeartEnUsFemale, VoiceCatalog.voices)
        assertEquals("zh_CN", zh?.language)
    }

    @Test
    fun `blank detection yields no switch`() {
        assertNull(LanguageVoiceRouter.route("", kokoroHeartEnUsFemale, VoiceCatalog.voices))
        assertNull(LanguageVoiceRouter.route("   ", kokoroHeartEnUsFemale, VoiceCatalog.voices))
    }

    @Test
    fun `routing is deterministic — identical inputs yield the identical voice`() {
        // Determinism is load-bearing: the PCM cache replays whatever
        // speakers a prior render chose, so re-rendering the same text
        // must resolve identically.
        val a = LanguageVoiceRouter.route("ja", kokoroHeartEnUsFemale, VoiceCatalog.voices)
        val b = LanguageVoiceRouter.route("ja", kokoroHeartEnUsFemale, VoiceCatalog.voices)
        assertEquals(a?.id, b?.id)
    }

    @Test
    fun `routing never crosses engine families`() {
        // Current voice is Kokoro; the only French candidate offered is a
        // Piper entry. Same-family policy must reject it → no switch.
        val syntheticFrenchPiper = CatalogEntry(
            id = "piper_fake_fr_FR_high",
            displayName = "Fake French Piper",
            language = "fr_FR",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Piper,
            piper = null,
            gender = VoiceGender.Female,
        )
        val catalog = listOf(kokoroHeartEnUsFemale, syntheticFrenchPiper)
        assertNull(LanguageVoiceRouter.route("fr", kokoroHeartEnUsFemale, catalog))
    }

    @Test
    fun `higher quality tier wins among same-gender same-language candidates`() {
        val current = CatalogEntry(
            id = "kokoro_cur_en_US_99",
            displayName = "Cur",
            language = "en_US",
            sizeBytes = 0L,
            qualityLevel = QualityLevel.High,
            engineType = EngineType.Kokoro(speakerId = 99),
            piper = null,
            gender = VoiceGender.Female,
        )
        fun frVoice(id: String, sid: Int, tier: QualityLevel) = CatalogEntry(
            id = id, displayName = id, language = "fr_FR", sizeBytes = 0L,
            qualityLevel = tier, engineType = EngineType.Kokoro(speakerId = sid),
            piper = null, gender = VoiceGender.Female,
        )
        val low = frVoice("kokoro_a_fr_FR_1", 1, QualityLevel.Low)
        val studio = frVoice("kokoro_z_fr_FR_2", 2, QualityLevel.Studio)
        // 'z…' sorts last by id, but Studio must beat the id tiebreak.
        val target = LanguageVoiceRouter.route("fr", current, listOf(current, low, studio))
        assertEquals("kokoro_z_fr_FR_2", target?.id)
    }
}
