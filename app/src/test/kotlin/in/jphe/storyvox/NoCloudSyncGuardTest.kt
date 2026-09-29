package `in`.jphe.storyvox

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1821 — Candela is local-only: the InstantDB cloud sync was removed.
 *
 * This guard fails if the sync layer creeps back: the `:core-sync` module,
 * an InstantDB reference in CODE (build scripts, Kotlin, XML, the CI workflow),
 * or any removed entry point (the sync packages, `SyncCoordinator`,
 * `InstantSession`/`InstantClient`, the secrets/inbox syncers, the
 * `auth/sync` route). Comments are ignored on purpose: history in KDoc is
 * fine; wiring is not. Word boundaries keep unrelated names such as the
 * Royal Road tag sync's `RoyalRoadTagSyncCoordinator` out of scope.
 *
 * A future sync backend should replace this guard deliberately, not by
 * accident.
 */
class NoCloudSyncGuardTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val forbidden = listOf(
        Regex("instant_?db", RegexOption.IGNORE_CASE),
        Regex("""in`?\.jphe\.storyvox\.sync\."""),
        Regex("""feature\.sync\."""),
        Regex("""["']:core-sync["']"""),
        Regex(""""auth/sync""""),
        Regex("""\b(SyncCoordinator|InstantSession|InstantClient|InstantBackend|SecretsSyncer|InboxSyncer|PassphraseManager|InboxSink)\b"""),
    )

    @Test
    fun `core-sync module is gone`() {
        assertFalse("core-sync/ must not exist (#1821)", File(root, "core-sync").exists())
        val settings = File(root, "settings.gradle.kts").readText()
        assertFalse("settings.gradle.kts must not include :core-sync", settings.contains(":core-sync"))
    }

    @Test
    fun `no code references instantdb or a removed sync entry point`() {
        val offenders = scanTargets().flatMap { file ->
            codeLines(file).mapNotNull { (n, code) ->
                forbidden.firstOrNull { it.containsMatchIn(code) }
                    ?.let { "${file.relativeTo(root)}:$n: ${code.trim().take(120)}" }
            }
        }
        assertTrue(
            "Cloud sync was removed in #1821 — these CODE lines reference it:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun scanTargets(): List<File> {
        val skipDirs = setOf(".git", "build", ".gradle", ".idea", "node_modules", "docs", "scratch")
        val self = File(root, "app/src/test/kotlin/in/jphe/storyvox/NoCloudSyncGuardTest.kt").canonicalFile
        return root.walkTopDown()
            .onEnter { it.name !in skipDirs }
            .filter { it.isFile }
            .filter { f ->
                f.extension in setOf("kt", "kts", "xml") ||
                    (f.extension in setOf("yml", "yaml") && f.path.contains(".github"))
            }
            .filter { it.canonicalFile != self }
            .toList()
    }

    /** Line number → code with comments stripped (`//`, block, `<!-- -->`, and `#` in YAML). */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val yaml = file.extension in setOf("yml", "yaml")
        var inBlock = false
        return file.readLines().mapIndexedNotNull { i, raw ->
            var line = raw
            if (inBlock) {
                val end = line.indexOf("*/").takeIf { it >= 0 } ?: line.indexOf("-->").takeIf { it >= 0 }
                if (end == null) return@mapIndexedNotNull null
                inBlock = false
                line = line.substring(end + 2)
            }
            listOf("/*" to "*/", "<!--" to "-->").forEach { (open, close) ->
                val s = line.indexOf(open)
                if (s >= 0) {
                    val e = line.indexOf(close, s + open.length)
                    line = if (e >= 0) line.removeRange(s, e + close.length) else line.substring(0, s).also { inBlock = true }
                }
            }
            line = if (yaml) line.substringBefore(" #").let { if (it.trimStart().startsWith("#")) "" else it }
            else line.replace(Regex("""(?<!:)//.*"""), "")
            (i + 1) to line
        }.filter { it.second.isNotBlank() }
    }
}
