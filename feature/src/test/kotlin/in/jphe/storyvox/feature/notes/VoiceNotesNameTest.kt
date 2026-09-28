package `in`.jphe.storyvox.feature.notes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #1657 product decision (2026-09-28): the feature is called **Voice Notes**.
 * The Notes list title must match the name its entry point announces
 * (`library_open_notes_cd`), in English and in Spanish, so a TalkBack user
 * who taps "Voice Notes" lands on a screen called "Voice Notes".
 */
class VoiceNotesNameTest {

    private fun res(path: String): String =
        listOf(File("src/main/res/$path"), File("feature/src/main/res/$path"))
            .firstOrNull { it.exists() }?.readText()
            ?: error("resource '$path' not found (cwd=${File(".").absoluteFile})")

    private fun string(xml: String, name: String): String =
        Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            ?: error("string '$name' not found")

    @Test fun `the Notes title matches the entry point's name in English`() {
        assertEquals(
            string(res("values/strings.xml"), "library_open_notes_cd"),
            string(res("values/strings_notes.xml"), "notes_title"),
        )
    }

    @Test fun `the Notes title matches the entry point's name in Spanish`() {
        assertEquals(
            string(res("values-es/strings.xml"), "library_open_notes_cd"),
            string(res("values-es/strings_notes.xml"), "notes_title"),
        )
    }
}
