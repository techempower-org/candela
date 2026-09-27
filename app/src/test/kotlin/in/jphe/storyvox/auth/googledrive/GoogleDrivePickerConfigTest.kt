package `in`.jphe.storyvox.auth.googledrive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1677 — pure pieces of the Drive connect + pick flow: the
 * reversed-client-id redirect scheme and the Picker page URL.
 */
class GoogleDrivePickerConfigTest {

    @Test
    fun reversedScheme_forStandardClientId() {
        assertEquals(
            "com.googleusercontent.apps.1234-abcdef",
            reversedClientIdScheme("1234-abcdef.apps.googleusercontent.com"),
        )
        assertEquals(
            "com.googleusercontent.apps.1234-abcdef",
            reversedClientIdScheme("  1234-abcdef.apps.googleusercontent.com \n"),
        )
    }

    @Test
    fun reversedScheme_nullForBlankOrNonStandardId() {
        assertNull(reversedClientIdScheme(""))
        assertNull(reversedClientIdScheme("not-a-google-id"))
        assertNull(reversedClientIdScheme(".apps.googleusercontent.com"))
    }

    @Test
    fun pickerUrl_carriesEverythingInTheFragment() {
        val url = buildPickerUrl(
            pageUrl = "https://candela.techempower.org/drive-picker.html",
            accessToken = "ya29.tok/en+x",
            apiKey = "AIzaKEY",
            appId = "123456789",
            returnUri = "candela://oauth/googledrive/picked",
            mimeTypes = listOf("application/pdf", "application/epub+zip"),
            languageTag = "es-MX",
        )
        assertTrue(url.startsWith("https://candela.techempower.org/drive-picker.html#"))
        assertFalse("no query string: the token must never reach the server", url.contains("?"))
        val fragment = url.substringAfter('#')
        val params = fragment.split('&').associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        assertEquals("ya29.tok/en+x", params["token"])
        assertEquals("AIzaKEY", params["key"])
        assertEquals("123456789", params["app"])
        assertEquals("candela://oauth/googledrive/picked", params["return"])
        assertEquals("application/pdf,application/epub+zip", params["mimes"])
        assertEquals("es-MX", params["hl"])
    }

    @Test
    fun pickableMimeTypes_coverDocsPdfEpubText() {
        val mimes = GoogleDrivePickerConfig.PICKABLE_MIME_TYPES
        assertTrue("application/vnd.google-apps.document" in mimes)
        assertTrue("application/pdf" in mimes)
        assertTrue("application/epub+zip" in mimes)
        assertTrue("text/plain" in mimes)
    }
}
