package `in`.jphe.storyvox.feature.techempower.learnpaths

/**
 * Issue #1464 — pure path-progress logic. No Android, no IO, no Flow: the
 * ViewModel feeds it the set of finished chapter ids (the existing
 * chapter-played state) and renders the result.
 *
 * Paths are ORDERED: the "next step" is the first step not yet finished, even
 * if a later one was finished out of order (a learner who skipped ahead is
 * still nudged back to the gap). A guide shared by two paths counts as done in
 * both — progress is keyed by chapter id, not by (path, step).
 */
object LearningPathProgress {

    fun progressFor(
        corpus: LearningPathsCorpus,
        path: LearningPath,
        finishedChapterIds: Set<String>,
    ): PathProgress {
        val done = path.steps.map { corpus.chapterIdFor(it) in finishedChapterIds }
        val nextIndex = done.indexOfFirst { !it }
        return PathProgress(
            pathId = path.id,
            stepDone = done,
            nextStepIndex = nextIndex.takeIf { it >= 0 },
        )
    }

    fun progressForAll(
        corpus: LearningPathsCorpus,
        finishedChapterIds: Set<String>,
    ): List<PathProgress> = corpus.paths.map { progressFor(corpus, it, finishedChapterIds) }

    /**
     * The path to nudge the learner toward on the "continue" card:
     *  1. the first path (in corpus order) that is started but not finished;
     *  2. else the first path not started yet;
     *  3. else null — every path is complete.
     * Empty paths (no steps) are never recommended.
     */
    fun recommended(progress: List<PathProgress>): PathProgress? =
        progress.firstOrNull { it.isInProgress }
            ?: progress.firstOrNull { it.total > 0 && it.completed == 0 }
}

data class PathProgress(
    val pathId: String,
    /** Per-step finished flag, in path order. */
    val stepDone: List<Boolean>,
    /** Index of the first unfinished step, or null when the path is complete. */
    val nextStepIndex: Int?,
) {
    val total: Int get() = stepDone.size
    val completed: Int get() = stepDone.count { it }
    val isComplete: Boolean get() = total > 0 && completed == total
    val isInProgress: Boolean get() = completed in 1 until total

    /** 0f..1f for a progress bar; an empty path reads as 0. */
    val fraction: Float get() = if (total == 0) 0f else completed.toFloat() / total
}
