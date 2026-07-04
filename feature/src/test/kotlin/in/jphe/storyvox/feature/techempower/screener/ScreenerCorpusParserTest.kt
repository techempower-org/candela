package `in`.jphe.storyvox.feature.techempower.screener

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1517 — pins the screener corpus decode contract. Pure JVM: the parser
 * takes a raw string (the ViewModel supplies the asset bytes), so no Android or
 * IO is needed here.
 */
class ScreenerCorpusParserTest {

    private val sample = """
        {
          "metadata": {
            "schemaVersion": 1,
            "provenance": "seed-sample",
            "verifiedDate": "2026-07-04",
            "source": "seed",
            "note": "n",
            "unknownFutureField": "ignored"
          },
          "questions": [
            { "id": "county", "type": "single_select",
              "prompt": { "en": "County?", "es": "¿Condado?" },
              "options": [
                { "id": "nevada", "label": { "en": "Nevada", "es": "Nevada" } }
              ] },
            { "id": "income_limited", "type": "boolean",
              "prompt": { "en": "Limited income?" } }
          ],
          "programs": [
            { "id": "liheap", "org": "Project GO", "category": "utilities",
              "name": { "en": "Energy help", "es": "Ayuda de energía" },
              "summary": { "en": "help", "es": "ayuda" },
              "phone": null, "applyUrl": "https://x", "verifiedDate": null,
              "criteria": [ { "questionId": "income_limited", "op": "is_true" } ] },
            { "id": "help_211", "name": { "en": "211" }, "summary": { "en": "call" },
              "phone": "211", "criteria": [] }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesMetadataQuestionsAndPrograms() {
        val corpus = ScreenerCorpusParser.parse(sample)

        assertEquals(1, corpus.metadata.schemaVersion)
        assertEquals("seed-sample", corpus.metadata.provenance)
        assertEquals("2026-07-04", corpus.metadata.verifiedDate)
        assertEquals(2, corpus.questions.size)
        assertEquals(2, corpus.programs.size)
    }

    @Test
    fun ignoresUnknownKeys() {
        // unknownFutureField in metadata must not break the decode.
        val corpus = ScreenerCorpusParser.parse(sample)
        assertEquals("seed", corpus.metadata.source)
    }

    @Test
    fun localizedFallsBackToEnglishWhenEsMissing() {
        val corpus = ScreenerCorpusParser.parse(sample)
        val incomeQ = corpus.questions.first { it.id == "income_limited" }
        // No "es" supplied → Spanish request falls back to English.
        assertEquals("Limited income?", incomeQ.prompt.get(spanish = true))
        assertEquals("Limited income?", incomeQ.prompt.get(spanish = false))
    }

    @Test
    fun localizedPrefersSpanishWhenPresent() {
        val corpus = ScreenerCorpusParser.parse(sample)
        val county = corpus.questions.first { it.id == "county" }
        assertEquals("¿Condado?", county.prompt.get(spanish = true))
        assertEquals("County?", county.prompt.get(spanish = false))
    }

    @Test
    fun questionTypeMapping() {
        val corpus = ScreenerCorpusParser.parse(sample)
        assertEquals(QuestionType.SINGLE_SELECT, corpus.questions.first { it.id == "county" }.questionType)
        assertEquals(QuestionType.BOOLEAN, corpus.questions.first { it.id == "income_limited" }.questionType)
    }

    @Test
    fun seedProvenanceIsNotVerified() {
        val corpus = ScreenerCorpusParser.parse(sample)
        assertFalse(corpus.metadata.isVerified)
    }

    @Test
    fun phoneIsNullWhenNotAsserted() {
        val corpus = ScreenerCorpusParser.parse(sample)
        assertNull(corpus.programs.first { it.id == "liheap" }.phone)
        assertEquals("211", corpus.programs.first { it.id == "help_211" }.phone)
    }

    @Test
    fun verifiedProvenanceFlipsBanner() {
        val verified = sample.replace("\"seed-sample\"", "\"techempower-verified\"")
        assertTrue(ScreenerCorpusParser.parse(verified).metadata.isVerified)
    }
}
