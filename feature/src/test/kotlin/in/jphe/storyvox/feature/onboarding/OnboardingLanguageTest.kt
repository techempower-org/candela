package `in`.jphe.storyvox.feature.onboarding

import `in`.jphe.storyvox.feature.settings.AppLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1466 — the pure pieces of the onboarding language pick. */
class OnboardingLanguageTest {

    @Test
    fun `explicit override wins over the device language`() {
        assertEquals(AppLanguage.English, effectiveOnboardingLanguage(AppLanguage.English, "es"))
        assertEquals(AppLanguage.Spanish, effectiveOnboardingLanguage(AppLanguage.Spanish, "en"))
    }

    @Test
    fun `follow-the-device resolves a Spanish phone to Spanish`() {
        assertEquals(AppLanguage.Spanish, effectiveOnboardingLanguage(AppLanguage.System, "es"))
        assertEquals(AppLanguage.English, effectiveOnboardingLanguage(AppLanguage.System, "en"))
        assertEquals(AppLanguage.English, effectiveOnboardingLanguage(AppLanguage.System, "fr"))
    }

    @Test
    fun `wikipedia code follows the pick`() {
        assertEquals("es", wikipediaCodeFor(AppLanguage.Spanish))
        assertEquals("en", wikipediaCodeFor(AppLanguage.English))
        assertNull(wikipediaCodeFor(AppLanguage.System))
    }

    @Test
    fun `wikipedia swaps only between the onboarding pair`() {
        assertTrue(shouldSwapWikipediaLanguage("en", "es"))
        assertTrue(shouldSwapWikipediaLanguage("es", "en"))
        assertTrue(shouldSwapWikipediaLanguage("", "es"))
        assertFalse(shouldSwapWikipediaLanguage("es", "es"))
        assertFalse(shouldSwapWikipediaLanguage("de", "es"))
        assertFalse(shouldSwapWikipediaLanguage("simple", "en"))
    }
}
