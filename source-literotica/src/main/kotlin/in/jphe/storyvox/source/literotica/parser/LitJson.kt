package `in`.jphe.storyvox.source.literotica.parser

import `in`.jphe.storyvox.source.literotica.HtmlText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import org.jsoup.Jsoup

/**
 * Tolerant readers over Literotica's `/api/3` JSON. Tree-based rather than
 * @Serializable classes because the API is undocumented and only observed
 * from the site's own SPA: list payloads arrive under different collection
 * keys per endpoint, and ids/ratings arrive as both strings and numbers.
 * Every accessor degrades to null instead of throwing.
 */
internal object LitJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Story(
        val slug: String,
        val title: String,
        val author: String,
        val description: String?,
        val category: String?,
        val rating: Double?,
        val pagesCount: Int?,
    )

    data class StoryList(val stories: List<Story>, val hasNext: Boolean)

    data class StoryPage(val story: Story?, val html: String, val plain: String)

    fun parseList(body: String): StoryList {
        val root = parse(body) ?: return StoryList(emptyList(), false)
        val items = collection(root).mapNotNull { (it as? JsonObject)?.let(::story) }
        return StoryList(items, hasNext = items.isNotEmpty() && hasMorePages(root))
    }

    /** One `stories/<slug>?params={"contentPage":N}` response: meta + that page's text. */
    fun parseStory(body: String): StoryPage? {
        val root = parse(body) ?: return null
        val sub = (root["submission"] as? JsonObject)
            ?: (root["story"] as? JsonObject)
            ?: root.takeIf { it.containsKey("title") }
        val story = sub?.let(::story)
        val raw = root.str("pageText") ?: sub?.str("pageText") ?: root.str("content") ?: ""
        if (story == null && raw.isBlank()) return null
        val (html, plain) = pageText(raw)
        return StoryPage(
            story = story?.copy(pagesCount = story.pagesCount ?: pagesCount(root)),
            html = html,
            plain = plain,
        )
    }

    /** Page text arrives as plain text with blank-line paragraphs, or as HTML. */
    fun pageText(raw: String): Pair<String, String> {
        val t = raw.trim()
        if (t.isEmpty()) return "" to ""
        return if ('<' in t && '>' in t) {
            val el = Jsoup.parseBodyFragment(t).body()
            el.html() to HtmlText.plain(el)
        } else {
            val paragraphs = t.replace("\r\n", "\n").split(Regex("\n{2,}|\n"))
                .map(String::trim).filter(String::isNotEmpty)
            paragraphs.joinToString("\n") { "<p>${it.replace("&", "&amp;").replace("<", "&lt;")}</p>" } to
                paragraphs.joinToString("\n\n")
        }
    }

    private fun story(o: JsonObject): Story? {
        val slug = slugOf(o.str("url") ?: o.str("slug") ?: return null) ?: return null
        val title = (o.str("title") ?: o.str("name"))?.trim()?.ifEmpty { null } ?: return null
        return Story(
            slug = slug,
            title = title,
            author = (o["author"] as? JsonObject)?.str("username")
                ?: o.str("author_name") ?: o.str("authorname") ?: "",
            description = o.str("description")?.trim()?.ifEmpty { null },
            category = (o["category_info"] as? JsonObject)?.str("name") ?: o.str("category_name"),
            rating = (o["rate_all"] as? JsonPrimitive)?.doubleOrNull,
            pagesCount = pagesCount(o),
        )
    }

    /** `url` is sometimes the bare slug, sometimes a full `/s/<slug>` URL. */
    fun slugOf(raw: String): String? {
        val s = raw.trim().trimEnd('/').substringAfterLast('/')
            .substringBefore('?').substringBefore('#')
        return s.lowercase().takeIf { it.matches(SLUG) }
    }

    private fun collection(root: JsonObject): List<Any?> =
        listOf("submissions", "data", "stories", "items")
            .firstNotNullOfOrNull { root[it] as? JsonArray }
            ?.toList().orEmpty()

    private fun hasMorePages(root: JsonObject): Boolean {
        val meta = (root["meta"] as? JsonObject) ?: root
        val current = meta.int("current_page") ?: meta.int("page") ?: return false
        val last = meta.int("last_page") ?: meta.int("pages") ?: meta.int("pages_count") ?: return false
        return current < last
    }

    private fun pagesCount(o: JsonObject): Int? =
        (o["meta"] as? JsonObject)?.int("pages_count")
            ?: o.int("pages_count") ?: o.int("number_of_pages")

    private fun parse(body: String): JsonObject? = try {
        json.parseToJsonElement(body) as? JsonObject
    } catch (_: Exception) {
        null
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.ifBlank { null }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private val SLUG = Regex("[a-z0-9][a-z0-9-]*")
}
