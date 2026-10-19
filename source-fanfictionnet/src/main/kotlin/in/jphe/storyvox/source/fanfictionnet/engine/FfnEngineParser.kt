package `in`.jphe.storyvox.source.fanfictionnet.engine

import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionStatus
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parsers for the FanFiction.net engine (FanFiction.net + FictionPress).
 * Selectors cross-checked against FanFicFare's `adapter_fanfictionnet.py`,
 * the long-lived reference scraper for these sites.
 *
 *  - Browse / search / "Just In" pages: one `div.z-list` per story.
 *  - Story page: `#profile_top` header, `select[name=chapter]` chapter list,
 *    `#storytext` body.
 */
object FfnEngineParser {

    data class ListItem(
        val storyId: String,
        val title: String,
        val author: String,
        val summary: String?,
        val coverUrl: String?,
        val chapters: Int?,
        val complete: Boolean,
        val meta: String,
    )

    data class ListResult(val items: List<ListItem>, val hasNext: Boolean)

    data class StoryHeader(
        val storyId: String,
        val title: String,
        val author: String,
        val authorId: String?,
        val summary: String?,
        val coverUrl: String?,
        val complete: Boolean,
        val categories: List<String>,
        val genres: List<String>,
        val words: Long?,
        val updatedAt: Long?,
        val chapterTitles: List<Pair<Int, String>>,
    )

    data class Chapter(val number: Int, val title: String, val html: String, val plain: String)

    private val STORY_HREF = Regex("""/s/(\d+)(?:/(\d+))?""")
    private val CHAPTERS = Regex("""Chapters:\s*([\d,]+)""")
    private val WORDS = Regex("""Words:\s*([\d,]+)""")
    private val LEADING_NUM = Regex("""^\s*\d+\.\s*""")

    // FFN's fixed genre vocabulary — used to pick the genre slot out of the
    // " - "-separated metadata line (same approach as FanFicFare).
    private val GENRES = setOf(
        "Adventure", "Angst", "Crime", "Drama", "Family", "Fantasy", "Friendship",
        "General", "Horror", "Humor", "Hurt/Comfort", "Mystery", "Parody", "Poetry",
        "Romance", "Sci-Fi", "Spiritual", "Supernatural", "Suspense", "Tragedy", "Western",
    )

    fun parseList(html: String, baseUrl: String): ListResult {
        val doc = Jsoup.parse(html, baseUrl)
        val items = doc.select("div.z-list").mapNotNull { parseListItem(it) }
        val hasNext = doc.select("a").any { a ->
            val t = a.text().trim()
            t.startsWith("Next") && a.attr("href").isNotBlank()
        }
        return ListResult(items, hasNext)
    }

    private fun parseListItem(el: Element): ListItem? {
        val link = el.selectFirst("a.stitle") ?: el.selectFirst("a[href^=/s/]") ?: return null
        val id = STORY_HREF.find(link.attr("href"))?.groupValues?.get(1) ?: return null
        val title = link.text().trim().ifEmpty { return null }
        val author = el.selectFirst("a[href^=/u/]")?.text()?.trim().orEmpty()
        val img = link.selectFirst("img") ?: el.selectFirst("img.cimage")
        val cover = img?.let { (it.attr("abs:data-original").ifBlank { it.attr("abs:src") }) }
            ?.takeIf { it.isNotBlank() && !it.contains("blank") }
        val indent = el.selectFirst("div.z-indent")
        val metaEl = indent?.selectFirst("div.xgray") ?: el.selectFirst("div.xgray")
        val meta = metaEl?.text()?.trim().orEmpty()
        val summary = indent?.clone()?.also { c -> c.select("div.xgray").remove() }?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        return ListItem(
            storyId = id,
            title = title,
            author = author,
            summary = summary,
            coverUrl = cover,
            chapters = CHAPTERS.find(meta)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull(),
            complete = meta.contains("Complete"),
            meta = meta,
        )
    }

    /** Null when the page is FFN's 200-status "Story Not Found" warning. */
    fun parseStory(html: String, baseUrl: String, storyId: String): StoryHeader? {
        val doc = Jsoup.parse(html, baseUrl)
        val top = doc.getElementById("profile_top") ?: return null
        val title = top.selectFirst("b.xcontrast_txt")?.text()?.trim()
            ?: top.selectFirst("b")?.text()?.trim()
            ?: return null
        val authorA = top.selectFirst("a[href^=/u/]")
        val meta = top.selectFirst("span.xgray")?.text().orEmpty()
        val summary = top.selectFirst("div[style*=margin-top]")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = top.selectFirst("img.cimage")?.let { it.attr("abs:data-original").ifBlank { it.attr("abs:src") } }
            ?.takeIf { it.isNotBlank() }
        val categories = doc.select("#pre_story_links a.xcontrast_txt").map { it.text().trim() }.filter { it.isNotEmpty() }
        val genres = meta.split(" - ").flatMap { seg -> seg.trim().split("/") }
            .map { it.trim() }.filter { it in GENRES || it == "Hurt" || it == "Comfort" }
            .map { if (it == "Hurt" || it == "Comfort") "Hurt/Comfort" else it }.distinct()
        val xutimes = top.select("span[data-xutime]").mapNotNull { it.attr("data-xutime").toLongOrNull() }
        val chapters = chapterOptions(doc).ifEmpty { listOf(1 to title) }
        return StoryHeader(
            storyId = storyId,
            title = title,
            author = authorA?.text()?.trim().orEmpty(),
            authorId = authorA?.attr("href")?.let { Regex("""/u/(\d+)""").find(it)?.groupValues?.get(1) },
            summary = summary,
            coverUrl = cover,
            complete = meta.contains("Status: Complete"),
            categories = categories,
            genres = genres,
            words = WORDS.find(meta)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull(),
            // First xutime is "Updated" when both are present; seconds → ms.
            updatedAt = xutimes.firstOrNull()?.times(1000),
            chapterTitles = chapters,
        )
    }

    private fun chapterOptions(doc: Document): List<Pair<Int, String>> {
        val select = doc.selectFirst("select[name=chapter]") ?: doc.getElementById("chap_select") ?: return emptyList()
        return select.select("option").mapNotNull { o ->
            val n = o.attr("value").toIntOrNull() ?: return@mapNotNull null
            n to o.text().replace(LEADING_NUM, "").trim().ifEmpty { "Chapter $n" }
        }.distinctBy { it.first }
    }

    fun parseChapter(html: String, baseUrl: String, number: Int): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val body = doc.getElementById("storytext") ?: doc.getElementById("storytextp") ?: return null
        body.select("div.google-auto-placed, ins, script, .a2a_kit").remove()
        val title = chapterOptions(doc).firstOrNull { it.first == number }?.second ?: "Chapter $number"
        return Chapter(number, title, body.html(), HtmlText.plain(body))
    }

    fun toChapterInfos(fictionId: String, chapters: List<Pair<Int, String>>): List<ChapterInfo> =
        chapters.mapIndexed { idx, (n, t) ->
            ChapterInfo(id = "$fictionId::$n", sourceChapterId = n.toString(), index = idx, title = t)
        }

    fun status(complete: Boolean): FictionStatus = if (complete) FictionStatus.COMPLETED else FictionStatus.ONGOING
}
