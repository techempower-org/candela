package `in`.jphe.storyvox.feature.techempower.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1518 — pins the call-cards decode contract + the never-invent-a-number
 * dial fallback. Pure JVM.
 */
class CallCardsParserTest {

    private val sample = """
        {
          "metadata": {
            "schemaVersion": 1,
            "provenance": "seed-sample",
            "verifiedDate": "2026-07-04",
            "futureField": "ignored"
          },
          "cards": [
            {
              "id": "liheap", "org": "Project GO",
              "title": { "en": "Energy help", "es": "Ayuda de energía" },
              "bestTimeToCall": { "en": "Morning", "es": "Por la mañana" },
              "phone": null,
              "whatToSay": [ { "en": "Hi", "es": "Hola" } ],
              "whatToAsk": [ { "en": "Funds?", "es": "¿Fondos?" } ],
              "captureFields": [ { "id": "notes", "label": { "en": "Notes", "es": "Notas" } } ],
              "verifiedDate": null
            },
            {
              "id": "help_211", "org": "211",
              "title": { "en": "211" }, "phone": "211",
              "whatToSay": [], "whatToAsk": [], "captureFields": [],
              "verifiedDate": "2026-07-04"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesMetadataAndCards() {
        val corpus = CallCardsParser.parse(sample)
        assertEquals("seed-sample", corpus.metadata.provenance)
        assertFalse(corpus.metadata.isVerified)
        assertEquals(2, corpus.cards.size)
    }

    @Test
    fun ignoresUnknownKeys() {
        assertTrue(CallCardsParser.parse(sample).cards.isNotEmpty())
    }

    @Test
    fun dialNumberFallsBackTo211WhenNoVerifiedNumber() {
        val corpus = CallCardsParser.parse(sample)
        val liheap = corpus.cards.first { it.id == "liheap" }
        // No verified number → never invents one; routes through 211.
        assertEquals("211", liheap.dialNumber())
    }

    @Test
    fun dialNumberUsesVerifiedNumberWhenPresent() {
        val corpus = CallCardsParser.parse(sample)
        val help = corpus.cards.first { it.id == "help_211" }
        assertEquals("211", help.dialNumber())
        assertEquals("211", help.phone)
    }

    @Test
    fun localizedContentFollowsLanguage() {
        val corpus = CallCardsParser.parse(sample)
        val liheap = corpus.cards.first { it.id == "liheap" }
        assertEquals("Ayuda de energía", liheap.title.get(spanish = true))
        assertEquals("Energy help", liheap.title.get(spanish = false))
        assertEquals("Por la mañana", liheap.bestTimeToCall!!.get(spanish = true))
    }

    @Test
    fun cardsCarryScriptChecklistAndCaptureFields() {
        val liheap = CallCardsParser.parse(sample).cards.first { it.id == "liheap" }
        assertEquals(1, liheap.whatToSay.size)
        assertEquals(1, liheap.whatToAsk.size)
        assertEquals("notes", liheap.captureFields.single().id)
    }
}
