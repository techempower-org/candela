package `in`.jphe.storyvox.feature.techempower.screener

/**
 * Issue #1517 — the answer a user has given to one [ScreenerQuestion].
 * [Bool] for yes/no questions, [Choice] for single-select. Absence of an entry
 * means "not answered yet".
 */
sealed interface Answer {
    data class Bool(val value: Boolean) : Answer
    data class Choice(val optionId: String) : Answer
}

/** Where a program lands after evaluating its criteria against the answers. */
enum class EligibilityBucket {
    /** All criteria satisfied (or no criteria) → surface as a likely match. */
    LIKELY,

    /** Criteria not contradicted, but some inputs are still unanswered. */
    MAYBE,

    /** At least one criterion is contradicted by an answer → not a match. */
    UNLIKELY,
}

data class ScreenerResult(
    val program: ScreenerProgram,
    val bucket: EligibilityBucket,
)

/**
 * Pure eligibility evaluation — no Android, no IO, no randomness. Fully covered
 * by plain-JVM unit tests.
 *
 * These are SEED rules ([CorpusMetadata.provenance] == seed-sample) — the
 * production corpus supplies verified criteria. The evaluator itself is content-
 * agnostic: it just applies whatever declarative criteria the corpus carries.
 */
object ScreenerEligibility {

    /** Evaluate a single program against the current answers. */
    fun evaluate(program: ScreenerProgram, answers: Map<String, Answer>): EligibilityBucket {
        if (program.criteria.isEmpty()) return EligibilityBucket.LIKELY

        var sawUnanswered = false
        for (c in program.criteria) {
            when (val answer = answers[c.questionId]) {
                null -> sawUnanswered = true
                else -> if (!satisfies(c, answer)) return EligibilityBucket.UNLIKELY
            }
        }
        return if (sawUnanswered) EligibilityBucket.MAYBE else EligibilityBucket.LIKELY
    }

    /**
     * Evaluate every program and return only those worth showing (LIKELY or
     * MAYBE), LIKELY first, preserving corpus order within a bucket. UNLIKELY
     * programs are dropped — a screener should not tell someone "you don't
     * qualify"; it surfaces what they *can* pursue.
     */
    fun results(corpus: ScreenerCorpus, answers: Map<String, Answer>): List<ScreenerResult> =
        corpus.programs
            .map { ScreenerResult(it, evaluate(it, answers)) }
            .filter { it.bucket != EligibilityBucket.UNLIKELY }
            .sortedBy { if (it.bucket == EligibilityBucket.LIKELY) 0 else 1 }

    private fun satisfies(criterion: Criterion, answer: Answer): Boolean = when (criterion.op) {
        Criterion.OP_IS_TRUE -> answer is Answer.Bool && answer.value
        Criterion.OP_IS_FALSE -> answer is Answer.Bool && !answer.value
        Criterion.OP_EQUALS -> answer is Answer.Choice && answer.optionId == criterion.value
        else -> false // unknown operator → treat as unsatisfiable, never a false positive
    }
}
