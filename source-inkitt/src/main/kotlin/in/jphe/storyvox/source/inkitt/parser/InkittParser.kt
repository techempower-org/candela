package `in`.jphe.storyvox.source.inkitt.parser

import `in`.jphe.storyvox.source.inkitt.HtmlText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import java.time.Instant

/**
 * Inkitt parsers, verified against the live site (2026-10). Story and chapter
 * pages are the only server-rendered surfaces (listing/genre/user pages are
 * client-rendered): a story page carries a schema.org `Book` JSON-LD block
 * plus a react-on-rails `StoryChaptersList` component whose props JSON holds
 * the chapter list; a chapter page renders the text in
 * `article#story-text-container`.
 */
internal object InkittParser {

    data class Story(
        val title: String,
        val author: String,
        val description: String?,
        val genres: List<String>,
        val contentRating: String?,
        val ongoing: Boolean,
        val cover: String?,
        val updatedAt: Long?,
        val chapters: List<ChapterRef>,
    )

    data class ChapterRef(val number: Int, val name: String)

    data class Chapter(val title: String?, val html: String, val plain: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseStoryPage(html: String, baseUrl: String): Story? {
        val doc = Jsoup.parse(html, baseUrl)
        val book = doc.select("script[type=application/ld+json]")
            .asSequence()
            .mapNotNull { parseObject(it.data()) }
            .firstOrNull { (it["@type"] as? JsonPrimitive)?.content == "Book" }
            ?: return null
        val title = book.str("name") ?: return null
        val chapters = doc.select("script.js-react-on-rails-component[data-component-name=StoryChaptersList]")
            .firstOrNull()
            ?.let { parseObject(it.data()) }
            ?.let { props ->
                (props["chapters"] as? JsonArray).orEmpty().mapNotNull { c ->
                    val o = c as? JsonObject ?: return@mapNotNull null
                    val n = (o["number"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return@mapNotNull null
                    ChapterRef(n, o.str("name") ?: "Chapter $n")
                }
            }
            .orEmpty()
        return Story(
            title = title,
            author = (book["author"] as? JsonObject)?.str("name").orEmpty(),
            description = book.str("description"),
            genres = when (val g = book["genre"]) {
                is JsonArray -> g.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(g.content)
                else -> emptyList()
            },
            contentRating = book.str("contentRating"),
            ongoing = !book.str("creativeWorkStatus").equals("Completed", ignoreCase = true),
            cover = (book["image"] as? JsonObject)?.str("url")
                ?: (book["image"] as? JsonPrimitive)?.content,
            updatedAt = (book.str("dateModified") ?: book.str("datePublished"))?.let(::isoMillis),
            chapters = chapters,
        )
    }

    fun parseChapterPage(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val article = doc.selectFirst("article#story-text-container") ?: return null
        val title = article.selectFirst("h2.chapter-head-title")?.text()?.trim()?.ifEmpty { null }
        article.select("h2.chapter-head-title, script, style, button").remove()
        val body = article.selectFirst("div.story-page-text") ?: article
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        return Chapter(title, body.html(), plain)
    }

    /** Tolerant reader for whatever the SPA's search endpoint returns (often not JSON). */
    fun parseSearchStories(body: String): List<Pair<String, String>> {
        val root = parseObject(body) ?: return emptyList()
        val items = listOf("stories", "data", "results", "items")
            .firstNotNullOfOrNull { root[it] as? JsonArray }
            ?: return emptyList()
        return items.mapNotNull { e ->
            val o = (e as? JsonObject) ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.content?.takeIf { it.all(Char::isDigit) }
                ?: return@mapNotNull null
            val title = o.str("title") ?: o.str("name") ?: return@mapNotNull null
            id to title
        }
    }

    private fun parseObject(raw: String): JsonObject? = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.ifEmpty { null }

    private fun isoMillis(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
