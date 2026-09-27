package `in`.jphe.storyvox.feature.techempower.learnpaths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1464 — decodes the bundled `learning_paths.json` and pins its shape,
 * so a bad JSON edit fails `testDebugUnitTest` instead of showing the load
 * error on a phone. Pure JVM (Gradle runs unit tests from the module dir).
 */
class LearningPathsCorpusTest {

    private val corpus: LearningPathsCorpus by lazy {
        val file = File("src/main/assets/${LearningPathsViewModel.CORPUS_ASSET}")
        LearningPathsParser.parse(file.readText())
    }

    @Test
    fun `parser tolerates unknown keys and defaults`() {
        val parsed = LearningPathsParser.parse(
            """{ "future": 1, "paths": [ { "id": "p", "title": { "en": "P" },
                 "steps": [ { "pageId": "x", "title": { "en": "X" }, "extra": true } ] } ] }""",
        )
        assertEquals(LearningPathsCorpus.DEFAULT_FICTION_ID, parsed.fictionId)
        assertEquals("X", parsed.paths.single().steps.single().title.get(spanish = true))
    }

    @Test
    fun `bundled corpus targets the TechEMPOWER Guides fiction`() {
        assertEquals("notion:guides", corpus.fictionId)
        assertTrue(corpus.paths.size >= 3)
    }

    @Test
    fun `bundled path ids are unique and every path has steps`() {
        val ids = corpus.paths.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        corpus.paths.forEach { assertFalse("${it.id} has no steps", it.steps.isEmpty()) }
    }

    @Test
    fun `bundled steps name compact 32-hex notion page ids`() {
        val hex32 = Regex("^[0-9a-f]{32}$")
        corpus.paths.flatMap { it.steps }.forEach { step ->
            assertTrue("bad pageId ${step.pageId}", hex32.matches(step.pageId))
        }
    }

    @Test
    fun `bundled copy is bilingual`() {
        corpus.paths.forEach { path ->
            assertNotNull("${path.id} title es", path.title.es)
            assertNotNull("${path.id} summary es", path.summary?.es)
            path.steps.forEach { step ->
                assertNotNull("${step.pageId} title es", step.title.es)
                assertNotNull("${step.pageId} why es", step.why?.es)
            }
        }
    }
}
