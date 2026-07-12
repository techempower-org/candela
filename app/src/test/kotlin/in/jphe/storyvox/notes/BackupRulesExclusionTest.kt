package `in`.jphe.storyvox.notes

import `in`.jphe.storyvox.data.notes.NotesDatabase
import `in`.jphe.storyvox.data.notes.NotesRepository
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice Notes (#1657, Phase 1) — **the privacy gate.** Asserts the backup
 * rules exclude the notes DB file (and every SQLite journal sibling) AND the
 * `recordings/` audio dir from BOTH `cloud-backup` and `device-transfer`
 * (spec §3.7). A mismatched or missing exclude silently uploads plaintext
 * notes + audio to Google's cloud — so this test must pass before any note
 * data ships.
 *
 * Hardened for #1659 so the gate can't silently rot:
 *  - Each of `notes.db`, `-wal`, `-shm`, `-journal` is asserted **individually**
 *    (the closing quote in the match is load-bearing — see [assertNoteExcludes]),
 *    so deleting ANY single journal-exclusion line fails the test. The `-wal`
 *    in particular holds recently-written, un-checkpointed note text under
 *    Room's default WAL mode; excluding only `notes.db` would leak it.
 *  - The DB-name prefix and the recordings dir are pinned to their
 *    source-of-truth consts ([NotesDatabase.NAME], [NotesRepository.RECORDINGS_SUBDIR]),
 *    so renaming a const without updating the XML breaks this test rather than
 *    silently un-protecting the data.
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
        // Every notes-DB journal sibling must be excluded INDIVIDUALLY. The
        // trailing `\"` in each match is load-bearing: without it the base
        // `path="notes.db"` line would satisfy the `-wal`/`-shm`/`-journal`
        // assertions by prefix, so deleting those exclude lines would go
        // undetected and leak un-checkpointed note text.
        for (dbFile in NOTES_DB_FILES) {
            assertTrue(
                "$where must exclude the notes DB file '$dbFile' (domain=\"database\")",
                section.contains("domain=\"database\" path=\"$dbFile\""),
            )
        }
        val recordings = NotesRepository.RECORDINGS_SUBDIR
        assertTrue(
            "$where must exclude the recordings/ audio dir — the path must match " +
                "NotesRepository.RECORDINGS_SUBDIR ('$recordings'); a rename that " +
                "misses the XML silently un-protects the audio (spec §3.7)",
            section.contains("domain=\"file\" path=\"$recordings/\""),
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

    private companion object {
        /**
         * The notes DB file plus every SQLite journal sibling that can hold
         * un-checkpointed note text: `-wal`/`-shm` (Room's default WAL mode)
         * and `-journal` (the rollback-journal fallback). Derived from
         * [NotesDatabase.NAME] so a DB-name rename that misses the XML fails
         * here instead of silently shipping the notes to the cloud.
         */
        val NOTES_DB_FILES: List<String> =
            listOf("", "-wal", "-shm", "-journal").map { NotesDatabase.NAME + it }
    }
}
