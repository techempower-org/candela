package `in`.jphe.storyvox.source.endless

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Id shapes. These look trivial and are not: a colon-less fiction id is
 * routed to Royal Road by `FictionSourceIdResolver` (#981), which compiles
 * clean and breaks only at runtime, and a chapter id that isn't
 * fiction-scoped breaks 1:1 joins on chapter id (#1261).
 */
class EndlessLitrpgIdsTest {

    @Test fun `fiction id carries the plugin prefix`() {
        assertEquals("endless:serial", EndlessLitrpgIds.FICTION_ID)
        assertTrue(EndlessLitrpgIds.FICTION_ID.startsWith("${EndlessLitrpgIds.PLUGIN_ID}:"))
    }

    @Test fun `chapter id is fiction-scoped with a double colon`() {
        assertEquals("endless:serial::42", EndlessLitrpgIds.chapterId(42))
    }

    @Test fun `chapter id round-trips`() {
        for (n in listOf(1, 5, 42, 1000)) {
            assertEquals(n, EndlessLitrpgIds.chapterNumberOf(EndlessLitrpgIds.chapterId(n)))
        }
    }

    @Test fun `a zero-padded chapter number still resolves`() {
        // The daemon names its artifacts 0005.mp3, so a zero-padded id is a
        // plausible thing to find persisted or deep-linked. Stranding a
        // chapter that is perfectly addressable would be the worse failure.
        assertEquals(5, EndlessLitrpgIds.chapterNumberOf("endless:serial::0005"))
    }

    @Test fun `a bare number resolves`() {
        assertEquals(7, EndlessLitrpgIds.chapterNumberOf("7"))
    }

    @Test fun `chapter zero is distinguished from unparseable`() {
        // "0" must parse to 0, not collapse to null via the padding strip.
        assertEquals(0, EndlessLitrpgIds.chapterNumberOf("endless:serial::0"))
    }

    @Test fun `an unparseable id is null rather than an exception`() {
        assertNull(EndlessLitrpgIds.chapterNumberOf("endless:serial::not-a-number"))
        assertNull(EndlessLitrpgIds.chapterNumberOf(""))
        assertNull(EndlessLitrpgIds.chapterNumberOf("endless:serial::"))
    }
}
