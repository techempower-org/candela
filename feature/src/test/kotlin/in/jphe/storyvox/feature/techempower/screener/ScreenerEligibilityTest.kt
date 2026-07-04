package `in`.jphe.storyvox.feature.techempower.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1517 — pins the pure eligibility evaluator. These are SEED rules; the
 * evaluator itself is content-agnostic (it applies whatever declarative criteria
 * the corpus carries), so this test locks the *logic*, not any program fact.
 */
class ScreenerEligibilityTest {

    private fun program(id: String, vararg criteria: Criterion) = ScreenerProgram(
        id = id,
        name = Localized("n"),
        summary = Localized("s"),
        criteria = criteria.toList(),
    )

    private fun corpusOf(vararg programs: ScreenerProgram) = ScreenerCorpus(
        metadata = CorpusMetadata(),
        questions = emptyList(),
        programs = programs.toList(),
    )

    @Test
    fun noCriteriaAlwaysLikely() {
        val p = program("help_211")
        assertEquals(EligibilityBucket.LIKELY, ScreenerEligibility.evaluate(p, emptyMap()))
    }

    @Test
    fun isTrueSatisfiedIsLikely() {
        val p = program("liheap", Criterion("income_limited", Criterion.OP_IS_TRUE))
        val answers = mapOf("income_limited" to Answer.Bool(true))
        assertEquals(EligibilityBucket.LIKELY, ScreenerEligibility.evaluate(p, answers))
    }

    @Test
    fun isTrueContradictedIsUnlikely() {
        val p = program("liheap", Criterion("income_limited", Criterion.OP_IS_TRUE))
        val answers = mapOf("income_limited" to Answer.Bool(false))
        assertEquals(EligibilityBucket.UNLIKELY, ScreenerEligibility.evaluate(p, answers))
    }

    @Test
    fun isFalseOperator() {
        val p = program("x", Criterion("q", Criterion.OP_IS_FALSE))
        assertEquals(EligibilityBucket.LIKELY, ScreenerEligibility.evaluate(p, mapOf("q" to Answer.Bool(false))))
        assertEquals(EligibilityBucket.UNLIKELY, ScreenerEligibility.evaluate(p, mapOf("q" to Answer.Bool(true))))
    }

    @Test
    fun equalsOperatorMatchesOptionId() {
        val p = program("water", Criterion("county", Criterion.OP_EQUALS, "nevada"))
        assertEquals(
            EligibilityBucket.LIKELY,
            ScreenerEligibility.evaluate(p, mapOf("county" to Answer.Choice("nevada"))),
        )
        assertEquals(
            EligibilityBucket.UNLIKELY,
            ScreenerEligibility.evaluate(p, mapOf("county" to Answer.Choice("other"))),
        )
    }

    @Test
    fun unansweredCriterionIsMaybe() {
        val p = program("liheap", Criterion("income_limited", Criterion.OP_IS_TRUE))
        assertEquals(EligibilityBucket.MAYBE, ScreenerEligibility.evaluate(p, emptyMap()))
    }

    @Test
    fun contradictionBeatsUnanswered() {
        // One criterion satisfied, one contradicted, one unanswered → UNLIKELY wins.
        val p = program(
            "multi",
            Criterion("a", Criterion.OP_IS_TRUE),
            Criterion("b", Criterion.OP_IS_TRUE),
            Criterion("c", Criterion.OP_IS_TRUE),
        )
        val answers = mapOf(
            "a" to Answer.Bool(true),   // satisfied
            "b" to Answer.Bool(false),  // contradicted
            // c unanswered
        )
        assertEquals(EligibilityBucket.UNLIKELY, ScreenerEligibility.evaluate(p, answers))
    }

    @Test
    fun unknownOperatorNeverFalsePositive() {
        val p = program("x", Criterion("q", "not_a_real_op"))
        // Unknown op must not silently qualify someone.
        assertEquals(EligibilityBucket.UNLIKELY, ScreenerEligibility.evaluate(p, mapOf("q" to Answer.Bool(true))))
    }

    @Test
    fun resultsDropUnlikelyAndSortLikelyFirst() {
        val corpus = corpusOf(
            program("maybe_one", Criterion("q1", Criterion.OP_IS_TRUE)),      // unanswered → MAYBE
            program("unlikely_one", Criterion("q2", Criterion.OP_IS_TRUE)),   // answered false → UNLIKELY
            program("always"),                                                // LIKELY
        )
        val answers = mapOf("q2" to Answer.Bool(false))
        val results = ScreenerEligibility.results(corpus, answers)

        // UNLIKELY dropped.
        assertEquals(2, results.size)
        assertTrue(results.none { it.program.id == "unlikely_one" })
        // LIKELY sorts before MAYBE.
        assertEquals("always", results[0].program.id)
        assertEquals(EligibilityBucket.LIKELY, results[0].bucket)
        assertEquals("maybe_one", results[1].program.id)
        assertEquals(EligibilityBucket.MAYBE, results[1].bucket)
    }
}
