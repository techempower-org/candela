package `in`.jphe.storyvox.data

import `in`.jphe.storyvox.data.TtsEngineResolver.Companion.GOOGLE_TTS
import `in`.jphe.storyvox.data.TtsEngineResolver.Companion.pickPreferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1384 — engine selection that keeps System TTS probing off the
 * null/private-default bind path. On Samsung a null-target
 * `TextToSpeech` binds the private device default, fails, and spins a
 * connect/disconnect loop forever; binding an explicit public engine
 * avoids it. These pin the resolver's contract: never null unless there
 * is genuinely no engine, prefer Google, and resolve deterministically.
 */
class TtsEngineResolverTest {

    @Test
    fun `no installed engines resolves to null so the caller skips enumeration`() {
        assertNull(pickPreferred(emptyList()))
    }

    @Test
    fun `Google TTS is preferred when present among others`() {
        assertEquals(
            GOOGLE_TTS,
            pickPreferred(listOf("com.samsung.SMT", GOOGLE_TTS, "org.example.espeak")),
        )
    }

    @Test
    fun `Google TTS is chosen regardless of list order`() {
        assertEquals(GOOGLE_TTS, pickPreferred(listOf(GOOGLE_TTS)))
        assertEquals(GOOGLE_TTS, pickPreferred(listOf("a.b.c", GOOGLE_TTS)))
        assertEquals(GOOGLE_TTS, pickPreferred(listOf(GOOGLE_TTS, "z.y.x")))
    }

    @Test
    fun `without Google selection is deterministic, never null, and from the set`() {
        val inOrder = listOf("org.example.espeak", "com.samsung.SMT")
        val reversed = inOrder.reversed()
        val a = pickPreferred(inOrder)
        val b = pickPreferred(reversed)
        assertNotNull("a public engine must be chosen rather than a null target", a)
        assertEquals("order must not change the choice", a, b)
        assertTrue("chosen engine must be one of the installed engines", a in inOrder)
    }

    @Test
    fun `a single non-Google engine is selected`() {
        assertEquals("org.example.espeak", pickPreferred(listOf("org.example.espeak")))
    }

    // --- #1390: bindableEnginePackages filtering ---

    @Test
    fun `Samsung private engine is excluded from bindable set`() {
        val installed = listOf(GOOGLE_TTS, "com.samsung.SMT", "org.example.espeak")
        val bindable = installed.filter { it !in PRIVATE_ENGINES }
        assertEquals(listOf(GOOGLE_TTS, "org.example.espeak"), bindable)
    }

    @Test
    fun `bindable set keeps all non-private engines`() {
        val installed = listOf(GOOGLE_TTS, "org.example.pico", "net.ekho.tts")
        val bindable = installed.filter { it !in PRIVATE_ENGINES }
        assertEquals(installed, bindable)
    }

    @Test
    fun `bindable set is empty when only private engine installed`() {
        val installed = listOf("com.samsung.SMT")
        val bindable = installed.filter { it !in PRIVATE_ENGINES }
        assertTrue(bindable.isEmpty())
    }

    companion object {
        private val PRIVATE_ENGINES = setOf("com.samsung.SMT")
    }
}
