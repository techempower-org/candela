package `in`.jphe.storyvox.feature.techempower.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #1580 — pure text assembly + toggle rules for the benefits read-aloud control. */
class ReadAloudScriptTest {

    // ── ReadAloudScript.build ─────────────────────────────────────────

    @Test
    fun `build joins parts with sentence breaks`() {
        assertEquals("Full name. Address.", ReadAloudScript.build("Full name", "Address"))
    }

    @Test
    fun `build keeps existing terminal punctuation`() {
        assertEquals(
            "Respond by Friday! Is this right? Note: yes.",
            ReadAloudScript.build("Respond by Friday!", "Is this right?", "Note: yes."),
        )
    }

    @Test
    fun `build drops null and blank parts`() {
        assertEquals("One. Two.", ReadAloudScript.build(null, "One", "   ", "", "Two", null))
    }

    @Test
    fun `build collapses OCR line breaks and double spaces`() {
        assertEquals(
            "Please respond by August 31, 2026 or benefits stop.",
            ReadAloudScript.build("  Please respond by\nAugust 31,   2026\n\tor benefits stop  "),
        )
    }

    @Test
    fun `build of nothing is empty`() {
        assertEquals("", ReadAloudScript.build(emptyList()))
        assertEquals("", ReadAloudScript.build(null, " "))
    }

    @Test
    fun `build accepts Spanish text unchanged apart from the break`() {
        assertEquals("Nombre completo: Ana. ¿Es correcto?", ReadAloudScript.build("Nombre completo: Ana", "¿Es correcto?"))
    }

    // ── ReadAloudScript.labeled ───────────────────────────────────────

    @Test
    fun `labeled joins label and value`() {
        assertEquals("Phone: 555 0100", ReadAloudScript.labeled("Phone", " 555 0100 "))
    }

    @Test
    fun `labeled blank value uses the empty phrase when given`() {
        assertEquals("Email: not filled in", ReadAloudScript.labeled("Email", "", "not filled in"))
    }

    @Test
    fun `labeled blank value without empty phrase is skipped`() {
        assertNull(ReadAloudScript.labeled("Email", "  "))
        assertEquals("", ReadAloudScript.build(ReadAloudScript.labeled("Email", null)))
    }

    @Test
    fun `labeled does not double a colon the label already has`() {
        assertEquals("Note: bring ID", ReadAloudScript.labeled("Note:", "bring ID"))
    }

    // ── ReadAloudSession ──────────────────────────────────────────────

    @Test
    fun `idle tap speaks`() {
        assertEquals(
            ReadAloudAction.Speak("hello"),
            ReadAloudSession.decide(key = "a", activeKey = null, busy = false, text = "hello"),
        )
    }

    @Test
    fun `tapping the speaking control stops it`() {
        assertEquals(
            ReadAloudAction.Stop,
            ReadAloudSession.decide(key = "a", activeKey = "a", busy = true, text = "hello"),
        )
    }

    @Test
    fun `tapping another control while speaking switches to its text`() {
        assertEquals(
            ReadAloudAction.Speak("other"),
            ReadAloudSession.decide(key = "b", activeKey = "a", busy = true, text = "other"),
        )
    }

    @Test
    fun `tapping after speech finished speaks again`() {
        assertEquals(
            ReadAloudAction.Speak("hello"),
            ReadAloudSession.decide(key = "a", activeKey = "a", busy = false, text = "hello"),
        )
    }

    @Test
    fun `blank script is ignored`() {
        assertEquals(ReadAloudAction.None, ReadAloudSession.decide("a", null, false, "  "))
    }

    @Test
    fun `stop still works even if the script became blank`() {
        assertEquals(ReadAloudAction.Stop, ReadAloudSession.decide("a", "a", true, ""))
    }

    @Test
    fun `only the owning control is active`() {
        assertTrue(ReadAloudSession.isActive("a", "a", busy = true))
        assertFalse(ReadAloudSession.isActive("b", "a", busy = true))
        assertFalse(ReadAloudSession.isActive("a", "a", busy = false))
        assertFalse(ReadAloudSession.isActive("a", null, busy = true))
    }
}
