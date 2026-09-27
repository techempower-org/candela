package `in`.jphe.storyvox.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** #1585 — tag-list → picker choice mapping for the language override. */
class AppLanguageTest {

    @Test
    fun `empty or null tags mean system default`() {
        assertEquals(AppLanguage.System, AppLanguage.fromTags(""))
        assertEquals(AppLanguage.System, AppLanguage.fromTags(null))
    }

    @Test
    fun `bare language tags map directly`() {
        assertEquals(AppLanguage.English, AppLanguage.fromTags("en"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTags("es"))
    }

    @Test
    fun `regional variants match on the primary subtag`() {
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTags("es-US"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTags("es-419"))
        assertEquals(AppLanguage.English, AppLanguage.fromTags("en_GB"))
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTags("ES-mx"))
    }

    @Test
    fun `only the first entry of a list counts`() {
        assertEquals(AppLanguage.Spanish, AppLanguage.fromTags("es-MX,en-US"))
    }

    @Test
    fun `unsupported languages read as system default`() {
        assertEquals(AppLanguage.System, AppLanguage.fromTags("fr-FR"))
        assertEquals(AppLanguage.System, AppLanguage.fromTags("zh-Hant-TW"))
    }

    @Test
    fun `every choice round-trips through its tag`() {
        AppLanguage.entries.forEach { assertEquals(it, AppLanguage.fromTags(it.tag)) }
    }
}
