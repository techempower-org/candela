package `in`.jphe.storyvox.source.tapas.parser

import `in`.jphe.storyvox.source.tapas.HtmlText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import org.jsoup.Jsoup
import java.time.Instant

/**
 * Tapas parsers, verified against the live site (2026-10): the novel search
 * page (`/search?t=NOVELS&q=…`, server-rendered), the series info page
 * (`/series/<slug>/info`), the XHR episodes JSON
 * (`/series/<id>/episodes?sort=OLDEST`), and the episode reader page
 * (`article.viewer__body`).
 */
internal object TapasParser {

    data class Card(val seriesId: String, val slug: String, val title: String, val cover: String?)

    data class SearchResult(val cards: List<Card>, val hasNext: Boolean)

    data class Info(
        val seriesId: String?,
        val title: String?,
        val description: String?,
        val author: String?,
        val genres: List<String>,
    )

    data class Episode(
        val id: String,
        val title: String,
        val scene: Int?,
        val readable: Boolean,
        val publishedAt: Long?,
    )

    data class EpisodesPage(val episodes: List<Episode>, val hasNext: Boolean)

    data class Chapter(val title: String?, val html: String, val plain: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val SERIES_HREF = Regex("""/series/([^/?#]+)""")
    private val PAGE_NUMBER = Regex("""[?&]pageNumber=(\d+)""")

    fun parseSearch(html: String, baseUrl: String, page: Int): SearchResult {
        val doc = Jsoup.parse(html, baseUrl)
        val bySeries = LinkedHashMap<String, Card>()
        for (a in doc.select("a[data-series-id][href*=/series/]")) {
            val id = a.attr("data-series-id").takeIf { it.isNotEmpty() && it.all(Char::isDigit) } ?: continue
            val slug = SERIES_HREF.find(a.attr("href"))?.groupValues?.get(1) ?: continue
            val title = a.text().trim()
            val cover = a.selectFirst("img")?.attr("abs:src")?.ifBlank { null }
            val existing = bySeries[id]
            bySeries[id] = Card(
                seriesId = id,
                slug = slug,
                // The thumb anchor has the image, the title anchor the text —
                // merge whichever arrives first with whatever the other adds.
                title = existing?.title?.ifBlank { title } ?: title,
                cover = existing?.cover ?: cover,
            )
        }
        val cards = bySeries.values.filter { it.title.isNotBlank() }
        val hasNext = doc.select("a[href*=pageNumber=]").any { a ->
            (PAGE_NUMBER.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull() ?: 0) > page
        }
        return SearchResult(cards, hasNext && cards.isNotEmpty())
    }

    fun parseInfo(html: String, baseUrl: String): Info {
        val doc = Jsoup.parse(html, baseUrl)
        // <title> is "Read <series> | Tapas Web Community" — sturdier than the
        // page's many p.title elements (the reader header uses the same class).
        val title = doc.title().removePrefix("Read ").substringBefore(" | Tapas").trim().ifEmpty { null }
        return Info(
            seriesId = doc.selectFirst("[data-series-id]")?.attr("data-series-id")?.ifBlank { null },
            title = title,
            description = doc.selectFirst(".js-series-description .description__body, .js-series-description")
                ?.text()?.trim()?.ifEmpty { null },
            author = doc.selectFirst("div.creator a, a.creator__item")?.text()?.trim()?.ifEmpty { null },
            genres = doc.select("a.genre-btn").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    fun parseEpisodes(body: String): EpisodesPage? {
        val root = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        val data = root["data"] as? JsonObject ?: return null
        val episodes = (data["episodes"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val free = (o["free"] as? JsonPrimitive)?.booleanOrNull ?: false
            val unlocked = (o["unlocked"] as? JsonPrimitive)?.booleanOrNull ?: false
            val mustPay = (o["must_pay"] as? JsonPrimitive)?.booleanOrNull ?: false
            Episode(
                id = id.toString(),
                title = (o["title"] as? JsonPrimitive)?.content?.trim().orEmpty(),
                scene = (o["scene"] as? JsonPrimitive)?.intOrNull,
                readable = free || unlocked || !mustPay,
                publishedAt = (o["publish_date"] as? JsonPrimitive)?.content?.let(::isoMillis),
            )
        }
        val hasNext = ((data["pagination"] as? JsonObject)?.get("has_next") as? JsonPrimitive)
            ?.booleanOrNull ?: false
        return EpisodesPage(episodes, hasNext)
    }

    fun parseEpisodePage(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val body = doc.selectFirst("article.viewer__body") ?: return null
        body.select("script, ins, .viewer-section--ad").remove()
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        val title = doc.selectFirst(".viewer__header .title")?.text()?.trim()?.ifEmpty { null }
        return Chapter(title, body.html(), plain)
    }

    private fun isoMillis(iso: String): Long? = try {
        Instant.parse(iso).toEpochMilli()
    } catch (_: Exception) {
        null
    }
}
