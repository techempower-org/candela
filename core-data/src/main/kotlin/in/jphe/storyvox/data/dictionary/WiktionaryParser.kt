package `in`.jphe.storyvox.data.dictionary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Issue #1230 — parse a Wiktionary REST `page/definition/{word}` response body
 * into the reader's [DictionaryEntry] list.
 *
 * ## Response shape
 * The endpoint returns a JSON object keyed by language code, each value an
 * array of part-of-speech groups:
 * ```json
 * { "en": [
 *     { "partOfSpeech": "Noun", "language": "English",
 *       "definitions": [ { "definition": "The <a href=\"…\">phenomenon</a> of …" } ] }
 * ] }
 * ```
 * The `definition` strings carry inline MediaWiki HTML (`<a>` links, `<i>`
 * emphasis, `&amp;` entities); [stripDefinitionHtml] reduces each to plain
 * prose for the bottom sheet.
 *
 * Pure + defensive: a malformed body, a missing language, or a group with no
 * usable glosses yields an empty list (the repository maps that to
 * [DictionaryResult.NotFound]) rather than throwing.
 */

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Cap senses per part of speech — Wiktionary can list a dozen rare/obsolete
 *  senses; the first few are what a reader interrupting their book wants. */
const val MAX_SENSES_PER_POS: Int = 6

/**
 * Parse [body] into definition groups for [languageCode] (default English).
 *
 * Falls back to the first language present when the requested code is absent,
 * so a word that only has, say, a Latin entry still returns something rather
 * than nothing. Senses are capped at [MAX_SENSES_PER_POS] per group; blank
 * glosses (groups that are pure usage examples) are dropped, and a group left
 * with no senses is omitted entirely.
 */
fun parseWiktionaryDefinitions(
    body: String,
    languageCode: String = "en",
): List<DictionaryEntry> {
    val root = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return emptyList()

    val groups = (root[languageCode] as? JsonArray)
        ?: root.values.firstOrNull { it is JsonArray } as? JsonArray
        ?: return emptyList()

    return groups.mapNotNull { element ->
        val group = element as? JsonObject ?: return@mapNotNull null
        val partOfSpeech = (group["partOfSpeech"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val definitions = group["definitions"] as? JsonArray ?: return@mapNotNull null

        val senses = definitions.asSequence()
            .mapNotNull { (it as? JsonObject)?.get("definition") as? JsonPrimitive }
            .map { stripDefinitionHtml(it.content) }
            .filter { it.isNotBlank() }
            .take(MAX_SENSES_PER_POS)
            .toList()

        if (senses.isEmpty()) null else DictionaryEntry(partOfSpeech, senses)
    }
}

private val HTML_TAG = Regex("<[^>]*>")
private val WHITESPACE = Regex("\\s+")
private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")

/**
 * Reduce a Wiktionary definition's inline HTML to plain reading text: drop
 * tags, decode the entities that survive (`&amp;`, `&quot;`, numeric refs),
 * collapse whitespace, trim. Pure Kotlin — no `android.text.Html` — so it runs
 * under the JVM unit-test harness. Mirrors the cleaner in `chapterPreviewText`;
 * kept local so the dictionary parser carries no cross-feature coupling.
 */
internal fun stripDefinitionHtml(raw: String): String {
    val noTags = HTML_TAG.replace(raw, " ")
    val decoded = decodeEntities(noTags)
    return WHITESPACE.replace(decoded, " ").trim()
}

private fun decodeEntities(s: String): String {
    if ('&' !in s) return s
    var out = s
    NAMED_ENTITIES.forEach { (entity, replacement) -> out = out.replace(entity, replacement) }
    out = NUMERIC_ENTITY.replace(out) { m ->
        val isHex = m.groupValues[1] == "x"
        val code = m.groupValues[2].toIntOrNull(if (isHex) 16 else 10)
        if (code != null && code in 1..0x10FFFF) {
            runCatching { String(Character.toChars(code)) }.getOrDefault(m.value)
        } else {
            m.value
        }
    }
    return out
}

private val NAMED_ENTITIES = listOf(
    "&nbsp;" to " ",
    "&amp;" to "&",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&apos;" to "'",
    "&hellip;" to "…",
    "&mdash;" to "—",
    "&ndash;" to "–",
    "&rsquo;" to "’",
    "&lsquo;" to "‘",
    "&rdquo;" to "”",
    "&ldquo;" to "“",
)
