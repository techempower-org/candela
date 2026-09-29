package `in`.jphe.storyvox.feature.reader.script

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #1369 — pure unit coverage for the script-manager helper functions
 * (tag normalization, duplicate naming, duration formatting). No Android
 * dependency; runs on the JVM.
 */
class ScriptManagerLogicTest {

    @Test fun `normalizeTags trims, drops blanks, and dedupes case-insensitively`() {
        assertEquals("talk, demo", normalizeTags("  talk , , demo , Talk "))
        assertEquals("", normalizeTags("   ,  , "))
        assertEquals("a, b, c", normalizeTags("a,b,c"))
    }

    @Test fun `duplicateTitle appends copy and falls back for blank titles`() {
        assertEquals("Talk (copy)", duplicateTitle("Talk"))
        assertEquals("Untitled (copy)", duplicateTitle(""))
        assertEquals("Untitled (copy)", duplicateTitle("   "))
    }

    @Test fun `duplicateTitle uses the localized labels (1819)`() {
        val es = ScriptCopyLabels(untitled = "Sin título", copyFormat = "%1\$s (copia)")
        assertEquals("Charla (copia)", duplicateTitle("Charla", es))
        assertEquals("Sin título (copia)", duplicateTitle("  ", es))
        val prefix = ScriptCopyLabels(untitled = "Untitled", copyFormat = "Copy of %1\$s")
        assertEquals("Copy of Talk", duplicateTitle("Talk", prefix))
    }

    @Test fun `formatDuration renders M_SS and H_MM_SS`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:45", formatDuration(45))
        assertEquals("3:20", formatDuration(200))
        assertEquals("1:00:00", formatDuration(3600))
        assertEquals("1:01:05", formatDuration(3665))
    }
}
