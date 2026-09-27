package `in`.jphe.storyvox.feature.techempower.learnpaths

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1464 — pins the learning-path progress contract: ordered next step,
 * shared-guide progress, and the continue-card recommendation. Pure JVM.
 */
class LearningPathProgressTest {

    private fun step(id: String) = PathStep(pageId = id, title = Localized(id))

    private val corpus = LearningPathsCorpus(
        fictionId = "notion:guides",
        paths = listOf(
            LearningPath("start", Localized("Start"), steps = listOf(step("aaa"), step("bbb"))),
            LearningPath("online", Localized("Online"), steps = listOf(step("ccc"), step("ddd"), step("eee"))),
            LearningPath("benefits", Localized("Benefits"), steps = listOf(step("fff"), step("bbb"))),
        ),
    )

    private fun ch(pageId: String) = "notion:guides::$pageId"

    private fun path(id: String) = corpus.paths.first { it.id == id }

    @Test
    fun `chapter id matches the notion anonymous-mode scheme`() {
        assertEquals("notion:guides::abc123", corpus.chapterIdFor(step("abc123")))
        // Hyphenated page ids collapse to the compact form source-notion uses.
        assertEquals(
            "notion:guides::6c979ba4e43f48d7a4836e0027ea4178",
            corpus.chapterIdFor(step("6c979ba4-e43f-48d7-a483-6e0027ea4178")),
        )
    }

    @Test
    fun `nothing finished means first step is next and progress is zero`() {
        val p = LearningPathProgress.progressFor(corpus, path("online"), emptySet())
        assertEquals(0, p.completed)
        assertEquals(3, p.total)
        assertEquals(0, p.nextStepIndex)
        assertEquals(0f, p.fraction, 0f)
        assertFalse(p.isInProgress)
        assertFalse(p.isComplete)
    }

    @Test
    fun `next step is the first unfinished even when a later step is done`() {
        val p = LearningPathProgress.progressFor(corpus, path("online"), setOf(ch("ddd")))
        assertEquals(listOf(false, true, false), p.stepDone)
        assertEquals(0, p.nextStepIndex)
        assertEquals(1, p.completed)
        assertTrue(p.isInProgress)
    }

    @Test
    fun `finishing every step completes the path with no next step`() {
        val p = LearningPathProgress.progressFor(corpus, path("start"), setOf(ch("aaa"), ch("bbb")))
        assertTrue(p.isComplete)
        assertNull(p.nextStepIndex)
        assertEquals(1f, p.fraction, 0f)
    }

    @Test
    fun `a guide shared by two paths counts as done in both`() {
        val all = LearningPathProgress.progressForAll(corpus, setOf(ch("bbb")))
        assertEquals(listOf(false, true), all.first { it.pathId == "start" }.stepDone)
        assertEquals(listOf(false, true), all.first { it.pathId == "benefits" }.stepDone)
    }

    @Test
    fun `chapters from other fictions are ignored`() {
        val p = LearningPathProgress.progressFor(corpus, path("start"), setOf("notion:resources::aaa", "aaa"))
        assertEquals(0, p.completed)
    }

    @Test
    fun `recommendation prefers an in-progress path over an unstarted earlier one`() {
        val all = LearningPathProgress.progressForAll(corpus, setOf(ch("ccc")))
        assertEquals("online", LearningPathProgress.recommended(all)?.pathId)
    }

    @Test
    fun `recommendation falls back to the first unstarted path`() {
        val all = LearningPathProgress.progressForAll(corpus, setOf(ch("aaa"), ch("bbb")))
        // "start" is complete; "benefits" is in progress via the shared bbb.
        assertEquals("benefits", LearningPathProgress.recommended(all)?.pathId)

        val fresh = LearningPathProgress.progressForAll(corpus, emptySet())
        assertEquals("start", LearningPathProgress.recommended(fresh)?.pathId)
    }

    @Test
    fun `recommendation is null when every path is complete`() {
        val everything = corpus.paths.flatMap { p -> p.steps.map { corpus.chapterIdFor(it) } }.toSet()
        val all = LearningPathProgress.progressForAll(corpus, everything)
        assertTrue(all.all { it.isComplete })
        assertNull(LearningPathProgress.recommended(all))
    }

    @Test
    fun `an empty path is never complete and never recommended`() {
        val empty = LearningPathsCorpus(paths = listOf(LearningPath("empty", Localized("Empty"))))
        val all = LearningPathProgress.progressForAll(empty, emptySet())
        assertFalse(all.single().isComplete)
        assertEquals(0f, all.single().fraction, 0f)
        assertNull(LearningPathProgress.recommended(all))
    }

    @Test
    fun `ui state derives progress and recommendation from finished ids`() {
        val state = LearningPathsUiState(corpus = corpus, finishedChapterIds = setOf(ch("ccc")))
        assertFalse(state.isLoading)
        assertEquals(1, state.progressOf("online")?.completed)
        assertEquals("online", state.recommended?.pathId)
        assertNull(state.selectedPath)
        assertEquals("benefits", state.copy(selectedPathId = "benefits").selectedPath?.id)
        assertTrue(LearningPathsUiState().isLoading)
    }
}
