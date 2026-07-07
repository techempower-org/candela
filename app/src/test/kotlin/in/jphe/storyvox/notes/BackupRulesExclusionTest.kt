package `in`.jphe.storyvox.notes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice Notes (#1657, Phase 1) — **the privacy gate.** Asserts the backup
 * rules exclude the notes DB file AND the `recordings/` audio dir from BOTH
 * `cloud-backup` and `device-transfer` (spec §3.7). A mismatched or missing
 * exclude silently uploads plaintext notes + audio to Google's cloud — so
 * this test must pass before any note data ships.
 *
 * Pure-JVM: reads the res XML by path and checks the exclude lines land in the
 * right `<domain>` sections (no Android XML parser dependency).
 */
class BackupRulesExclusionTest {

    private fun rulesFile(name: String): File =
        listOf(File("src/main/res/xml/$name"), File("app/src/main/res/xml/$name"))
            .firstOrNull { it.exists() }
            ?: error("backup rules file '$name' not found (cwd=${File(".").absoluteFile})")

    /** The substring of [xml] inside `<tag> … </tag>`. */
    private fun section(xml: String, tag: String): String {
        val start = xml.indexOf("<$tag>")
        val end = xml.indexOf("</$tag>")
        assertTrue("<$tag> section present", start >= 0 && end > start)
        return xml.substring(start, end)
    }

    private fun assertNoteExcludes(section: String, where: String) {
        assertTrue(
            "$where must exclude the notes.db database file",
            section.contains("domain=\"database\" path=\"notes.db"),
        )
        assertTrue(
            "$where must exclude the recordings/ audio dir",
            section.contains("domain=\"file\" path=\"recordings/"),
        )
    }

    @Test
    fun dataExtractionRules_excludeNotesAndRecordings_inBothDomains() {
        val xml = rulesFile("data_extraction_rules.xml").readText()
        assertNoteExcludes(section(xml, "cloud-backup"), "cloud-backup")
        assertNoteExcludes(section(xml, "device-transfer"), "device-transfer")
    }

    @Test
    fun legacyBackupRules_excludeNotesAndRecordings() {
        val xml = rulesFile("backup_rules.xml").readText()
        assertNoteExcludes(section(xml, "full-backup-content"), "full-backup-content")
    }
}
