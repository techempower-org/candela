package `in`.jphe.storyvox.source.wattpad.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Tolerant readers over Wattpad's public JSON (`/api/v3`, `/v4/search`).
 * Tree-based rather than @Serializable classes because Wattpad's `fields=`
 * projection makes every property optional and ids arrive as both strings
 * and numbers depending on the endpoint.
 */
internal object WattpadJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Story(
        val id: String,
        val title: String,
        val author: String,
        val cover: String?,
        val description: String?,
        val tags: List<String>,
        val parts: Int?,
        val completed: Boolean,
        val mature: Boolean,
    )

    data class Part(val id: String, val title: String, val wordCount: Int?, val createdAt: Long?)

    data class StoryList(val stories: List<Story>, val hasNext: Boolean)

    fun parseList(body: String): StoryList {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return StoryList(emptyList(), false)
        val stories = (root["stories"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::story) }
        val next = root.str("nextUrl")
        return StoryList(stories, hasNext = !next.isNullOrBlank() && stories.isNotEmpty())
    }

    fun parseStory(body: String): Pair<Story, List<Part>>? {
        val o = json.parseToJsonElement(body) as? JsonObject ?: return null
        val s = story(o) ?: return null
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            if (p.bool("deleted") == true || p.bool("draft") == true) return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            Part(
                id = id,
                title = p.str("title")?.trim().orEmpty().ifEmpty { "Part" },
                wordCount = (p["wordCount"] as? JsonPrimitive)?.intOrNull,
                createdAt = p.str("createDate")?.let(::isoMillis),
            )
        }
        return s to parts
    }

    private fun story(o: JsonObject): Story? {
        val id = o.str("id") ?: return null
        val title = o.str("title")?.trim()?.ifEmpty { null } ?: return null
        return Story(
            id = id,
            title = title,
            author = (o["user"] as? JsonObject)?.str("name").orEmpty(),
            cover = o.str("cover")?.takeIf { it.startsWith("http") },
            description = o.str("description")?.trim()?.ifEmpty { null },
            tags = (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
            parts = (o["numParts"] as? JsonPrimitive)?.intOrNull,
            completed = o.bool("completed") == true,
            mature = o.bool("mature") == true,
        )
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.longOrNull?.let { n -> n != 0L } }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

    private fun isoMillis(s: String): Long? = runCatching { java.time.Instant.parse(s).toEpochMilli() }.getOrNull()
}
