package `in`.jphe.storyvox.data.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1230 — Wiktionary REST parsing unit coverage. Fixtures mirror the
 * real `en.wiktionary.org/api/rest_v1/page/definition/{word}` shape (object
 * keyed by language → array of part-of-speech groups → `definitions[].definition`
 * carrying inline MediaWiki HTML). Hermetic: no network.
 */
class WiktionaryParserTest {

    @Test
    fun `parses multiple parts of speech with HTML stripped`() {
        val body = """
            {
              "en": [
                {
                  "partOfSpeech": "Noun",
                  "language": "English",
                  "definitions": [
                    { "definition": "The <a rel=\"mw:WikiLink\" href=\"/wiki/phenomenon\">phenomenon</a> of finding pleasant things by chance." },
                    { "definition": "An instance of such an occurrence." }
                  ]
                },
                {
                  "partOfSpeech": "Verb",
                  "language": "English",
                  "definitions": [
                    { "definition": "To <i>move</i> swiftly &amp; lightly." }
                  ]
                }
              ]
            }
        """.trimIndent()

        val entries = parseWiktionaryDefinitions(body)

        assertEquals(2, entries.size)
        assertEquals("Noun", entries[0].partOfSpeech)
        assertEquals(
            listOf(
                "The phenomenon of finding pleasant things by chance.",
                "An instance of such an occurrence.",
            ),
            entries[0].senses,
        )
        assertEquals("Verb", entries[1].partOfSpeech)
        assertEquals(listOf("To move swiftly & lightly."), entries[1].senses)
    }

    @Test
    fun `drops groups whose definitions are all blank after stripping`() {
        val body = """
            {
              "en": [
                { "partOfSpeech": "Noun", "definitions": [ { "definition": "<span></span>" }, { "definition": "   " } ] },
                { "partOfSpeech": "Verb", "definitions": [ { "definition": "To do a thing." } ] }
              ]
            }
        """.trimIndent()

        val entries = parseWiktionaryDefinitions(body)

        assertEquals(1, entries.size)
        assertEquals("Verb", entries[0].partOfSpeech)
    }

    @Test
    fun `caps senses per part of speech`() {
        val defs = (1..20).joinToString(",") { """{ "definition": "Sense $it." }""" }
        val body = """{ "en": [ { "partOfSpeech": "Noun", "definitions": [ $defs ] } ] }"""

        val entries = parseWiktionaryDefinitions(body)

        assertEquals(MAX_SENSES_PER_POS, entries[0].senses.size)
        assertEquals("Sense 1.", entries[0].senses.first())
    }

    @Test
    fun `falls back to the first language when requested code is absent`() {
        val body = """
            { "la": [ { "partOfSpeech": "Noun", "definitions": [ { "definition": "A Latin word." } ] } ] }
        """.trimIndent()

        val entries = parseWiktionaryDefinitions(body, languageCode = "en")

        assertEquals(1, entries.size)
        assertEquals(listOf("A Latin word."), entries[0].senses)
    }

    @Test
    fun `tolerates a missing part of speech label`() {
        val body = """{ "en": [ { "definitions": [ { "definition": "No POS here." } ] } ] }"""

        val entries = parseWiktionaryDefinitions(body)

        assertEquals(1, entries.size)
        assertEquals("", entries[0].partOfSpeech)
        assertEquals(listOf("No POS here."), entries[0].senses)
    }

    @Test
    fun `malformed or empty bodies yield an empty list`() {
        assertTrue(parseWiktionaryDefinitions("").isEmpty())
        assertTrue(parseWiktionaryDefinitions("not json at all").isEmpty())
        assertTrue(parseWiktionaryDefinitions("{}").isEmpty())
        assertTrue(parseWiktionaryDefinitions("[]").isEmpty())
        assertTrue(parseWiktionaryDefinitions("""{ "en": [] }""").isEmpty())
    }

    @Test
    fun `stripDefinitionHtml decodes entities and collapses whitespace`() {
        assertEquals(
            "A & B \"quoted\" — done",
            stripDefinitionHtml("A &amp; B &quot;quoted&quot; &mdash;   done"),
        )
        assertEquals("plain text", stripDefinitionHtml("<b>plain</b>\n<i>text</i>"))
        assertEquals("café", stripDefinitionHtml("caf&#233;"))
    }
}
