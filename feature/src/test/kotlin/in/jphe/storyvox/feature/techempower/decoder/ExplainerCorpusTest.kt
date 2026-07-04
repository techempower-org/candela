package `in`.jphe.storyvox.feature.techempower.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1516 — pins corpus decode + explainer lookup, including the alias and
 * unknown-form paths that drive the honest fallback.
 */
class ExplainerCorpusTest {

    private val sample = """
        {
          "metadata": {
            "schemaVersion": 1,
            "provenance": "seed-sample",
            "verifiedDate": "2026-07-04",
            "freshnessMaxAgeDays": 365,
            "source": "seed",
            "futureUnknownField": "ignored"
          },
          "explainers": [
            {
              "formNumber": "NA 200",
              "aliases": ["NA200", "NA-200"],
              "title": { "en": "Notice of Action (NA 200)", "es": "Aviso (NA 200)" },
              "whatItMeans": { "en": "means-en", "es": "means-es" },
              "whyYouGotIt": { "en": "why-en" },
              "whatToDo": { "en": "todo-en", "es": "todo-es" },
              "phone": "211",
              "verifiedAt": "2026-07-04",
              "source": { "en": "seed" }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesMetadataAndExplainers() {
        val corpus = ExplainerCorpusParser.parse(sample)
        assertEquals("seed-sample", corpus.metadata.provenance)
        assertEquals(365L, corpus.metadata.freshnessMaxAgeDays)
        assertEquals(1, corpus.explainers.size)
        assertFalse(corpus.metadata.isVerified)
    }

    @Test
    fun findResolvesByCanonicalForm() {
        val corpus = ExplainerCorpusParser.parse(sample)
        assertNotNull(corpus.find("NA 200"))
        assertEquals("Notice of Action (NA 200)", corpus.find("NA 200")!!.title.get(spanish = false))
    }

    @Test
    fun findResolvesByAliasAndNormalization() {
        val corpus = ExplainerCorpusParser.parse(sample)
        assertNotNull(corpus.find("NA200"))
        assertNotNull(corpus.find("na-200"))
        assertNotNull(corpus.find("Na 200"))
    }

    @Test
    fun findReturnsNullForUnknownForm() {
        val corpus = ExplainerCorpusParser.parse(sample)
        assertNull(corpus.find("XX 999"))
    }

    @Test
    fun localizedFallsBackToEnglish() {
        val corpus = ExplainerCorpusParser.parse(sample)
        val e = corpus.find("NA 200")!!
        assertEquals("means-es", e.whatItMeans.get(spanish = true))
        assertEquals("why-en", e.whyYouGotIt.get(spanish = true)) // no es → fallback
    }

    @Test
    fun ignoresUnknownKeys() {
        // futureUnknownField in metadata must not break decode.
        assertTrue(ExplainerCorpusParser.parse(sample).explainers.isNotEmpty())
    }
}
