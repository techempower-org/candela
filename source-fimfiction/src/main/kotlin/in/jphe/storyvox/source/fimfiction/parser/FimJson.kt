package `in`.jphe.storyvox.source.fimfiction.parser

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * FimFiction's legacy anonymous JSON API (`/api/story.php?story=<id>`),
 * verified against the live endpoint (2026-10). The modern v2 API needs an
 * OAuth application; the legacy one is anonymous and carries everything a
 * detail page needs, chapter list included.
 */
@Serializable
internal data class FimStoryEnvelope(
    val story: FimStory? = null,
    val error: String? = null,
)

@Serializable
internal data class FimStory(
    val id: Long,
    val title: String = "",
    val url: String? = null,
    @SerialName("short_description") val shortDescription: String? = null,
    val description: String? = null,
    val author: FimAuthor? = null,
    @SerialName("chapter_count") val chapterCount: Int? = null,
    val chapters: List<FimChapter> = emptyList(),
    @SerialName("content_rating_text") val contentRatingText: String? = null,
    val status: String? = null,
    val words: Long? = null,
    val views: Long? = null,
    @SerialName("total_views") val totalViews: Long? = null,
    val likes: Long? = null,
    val image: String? = null,
    @SerialName("full_image") val fullImage: String? = null,
    @SerialName("date_modified") val dateModified: Long? = null,
    /** Present as `{"Adventure": true, …}` on stories that carry categories; null otherwise. */
    val categories: Map<String, Boolean>? = null,
)

@Serializable
internal data class FimAuthor(val id: Long? = null, val name: String = "")

@Serializable
internal data class FimChapter(
    val id: Long,
    val title: String = "",
    val words: Int? = null,
    val views: Long? = null,
    val link: String? = null,
    @SerialName("date_modified") val dateModified: Long? = null,
)

internal object FimJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** null when the body isn't the API's JSON at all (an HTML error page, say). */
    fun parseStory(body: String): FimStoryEnvelope? = try {
        json.decodeFromString<FimStoryEnvelope>(body)
    } catch (_: Exception) {
        null
    }
}
