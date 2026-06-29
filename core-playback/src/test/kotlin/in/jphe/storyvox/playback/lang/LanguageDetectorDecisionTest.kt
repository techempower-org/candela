package `in`.jphe.storyvox.playback.lang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #1233 — the Android-free decision core of language detection.
 * [decideDetection] holds the thresholding rules and [baseLanguage] the
 * tag normalisation; the `TextClassifier`-backed detector is a thin shell
 * over these, so testing them directly covers the branching logic without
 * Robolectric.
 */
class LanguageDetectorDecisionTest {

    private val minLen = 16
    private val minConf = 0.55f

    @Test
    fun `text shorter than the minimum is rejected`() {
        // "Bonjour" — confidently French, but too short to trust.
        assertNull(decideDetection("fr", 0.99f, textLength = 7, minLen, minConf))
    }

    @Test
    fun `confidence below the floor is rejected`() {
        assertNull(decideDetection("fr", 0.40f, textLength = 80, minLen, minConf))
    }

    @Test
    fun `a blank or absent tag is rejected`() {
        assertNull(decideDetection(null, 0.99f, textLength = 80, minLen, minConf))
        assertNull(decideDetection("", 0.99f, textLength = 80, minLen, minConf))
        assertNull(decideDetection("   ", 0.99f, textLength = 80, minLen, minConf))
    }

    @Test
    fun `a confident, long-enough detection is accepted and normalised`() {
        val result = decideDetection("fr", 0.92f, textLength = 80, minLen, minConf)
        assertEquals(DetectedLanguage("fr", 0.92f), result)
    }

    @Test
    fun `script and region suffixes are normalised to the base subtag`() {
        assertEquals("zh", decideDetection("zh-Hant", 0.8f, 80, minLen, minConf)?.languageCode)
        assertEquals("pt", decideDetection("pt_BR", 0.8f, 80, minLen, minConf)?.languageCode)
    }

    @Test
    fun `thresholds are inclusive at the boundary`() {
        // textLength == minLen and confidence == minConf both pass (the
        // rejects are strict `<`).
        val result = decideDetection("ja", minConf, textLength = minLen, minLen, minConf)
        assertEquals(DetectedLanguage("ja", minConf), result)
    }

    @Test
    fun `baseLanguage strips region, script, case and whitespace`() {
        assertEquals("fr", baseLanguage("fr-FR"))
        assertEquals("en", baseLanguage("en_US"))
        assertEquals("zh", baseLanguage("  ZH-Hant "))
        assertEquals("ja", baseLanguage("JA"))
        assertEquals("", baseLanguage(""))
        assertEquals("", baseLanguage("   "))
    }
}
