package `in`.jphe.storyvox.notes

import `in`.jphe.storyvox.StoryvoxApp
import `in`.jphe.storyvox.data.notes.NotesStartup
import java.io.File
import java.lang.reflect.ParameterizedType
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice Notes (#1657) — pins that the orphan-audio sweep is actually wired
 * into app startup. `NotesRepository.sweepOrphanAudio()` shipped tested but
 * never called; this is the gate that fails if the hook is dropped again.
 *
 * `StoryvoxApp` can't be JVM-instantiated (Hilt graph), so the behaviour lives
 * in [NotesStartup] (tested in `:core-data`) and this test checks the two
 * wiring facts: the app injects a `Lazy<NotesStartup>`, and `onCreate` hands
 * it the deferred-init scope. Pure-JVM, reads the source by path like
 * [BackupRulesExclusionTest].
 */
class NotesStartupWiringTest {

    private fun appSource(): String =
        listOf(
            File("src/main/kotlin/in/jphe/storyvox/StoryvoxApp.kt"),
            File("app/src/main/kotlin/in/jphe/storyvox/StoryvoxApp.kt"),
        ).firstOrNull { it.exists() }?.readText()
            ?: error("StoryvoxApp.kt not found (cwd=${File(".").absoluteFile})")

    @Test
    fun storyvoxApp_injectsALazyNotesStartup() {
        val field = StoryvoxApp::class.java.declaredFields.firstOrNull { f ->
            val type = f.genericType as? ParameterizedType ?: return@firstOrNull false
            type.rawType == dagger.Lazy::class.java &&
                type.actualTypeArguments.singleOrNull() == NotesStartup::class.java
        }
        assertTrue(
            "StoryvoxApp must @Inject a dagger.Lazy<NotesStartup> so the orphan-audio sweep runs at startup",
            field != null,
        )
    }

    @Test
    fun storyvoxApp_onCreate_startsTheSweepOnTheInitScope() {
        val src = appSource()
        val onCreate = src.substringAfter("override fun onCreate()", missingDelimiterValue = "")
        assertTrue("onCreate present", onCreate.isNotEmpty())
        assertTrue(
            "StoryvoxApp.onCreate must call notesStartup.get().start(initScope) — " +
                "the off-main, never-crashing, once-per-process orphan-audio sweep hook",
            Regex("""notesStartup\.get\(\)\.start\(\s*initScope\s*\)""").containsMatchIn(onCreate),
        )
    }
}
