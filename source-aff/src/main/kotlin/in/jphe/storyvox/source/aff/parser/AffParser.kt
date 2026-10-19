package `in`.jphe.storyvox.source.aff.parser

import `in`.jphe.storyvox.source.aff.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * Parsers for Adult-FanFiction.org's 2026 site software (verified against
 * live pages, Oct 2026): `div.story-entry` cards on archive index / search
 * pages, a `select.chapter-select` chapter list and `div.chapter-body` text
 * on `story.php?no=<id>&chapter=<n>`.
 */
internal object AffParser {

    data class Entry(
        val storyNo: String,
        val title: String,
        val author: String,
        val description: String?,
        val tags: List<String>,
        val chapters: Int?,
        val location: String?,
    )

    data class ListResult(val entries: List<Entry>, val hasNext: Boolean)

    data class Story(
        val title: String,
        val author: String,
        val category: String?,
        val chapters: List<Pair<Int, String>>,
    )

    data class Chapter(val title: String?, val html: String, val plain: String)

    private val STORY_NO = Regex("""story\.php\?no=(\d+)""")
    private val CH_PREFIX = Regex("""^Chapter\s+\d+\s*[-–:]\s*""", RegexOption.IGNORE_CASE)

    fun parseList(html: String, baseUrl: String): ListResult {
        val doc = Jsoup.parse(html, baseUrl)
        val entries = doc.select("div.story-entry").mapNotNull(::entry)
        val hasNext = doc.select("a.pagination-btn").any { it.text().contains("Next", ignoreCase = true) }
        return ListResult(entries, hasNext)
    }

    private fun entry(el: Element): Entry? {
        val a = el.selectFirst("a.story-title") ?: return null
        val no = STORY_NO.find(a.attr("href"))?.groupValues?.get(1) ?: return null
        val meta = el.select("div.story-meta-item").associate { item ->
            val key = item.selectFirst("strong")?.text()?.trimEnd(':')?.trim().orEmpty()
            key to item.ownText().trim()
        }
        return Entry(
            storyNo = no,
            title = a.text().trim().ifEmpty { return null },
            author = el.selectFirst("a.story-author")?.text()?.trim().orEmpty(),
            description = el.selectFirst("div.story-description")?.let { HtmlText.plain(it) }?.ifBlank { null },
            tags = el.select("span.story-tag").map { it.text().trim() }.filter { it.isNotEmpty() },
            chapters = meta["Chapters"]?.replace(",", "")?.toIntOrNull(),
            location = el.selectFirst("div.story-location")?.ownText()?.trim()?.ifEmpty { null },
        )
    }

    fun parseStory(html: String, baseUrl: String): Story? {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.selectFirst(".story-header-left h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null
        if (doc.selectFirst("div.chapter-body") == null && doc.selectFirst("select.chapter-select") == null) return null
        val author = doc.selectFirst(".story-header-author a")?.text()?.trim().orEmpty()
        val category = doc.selectFirst(".story-header-category span:not(.material-symbols-outlined)")
            ?.text()?.trim()?.ifEmpty { null }
        val chapters = doc.selectFirst("select.chapter-select")?.select("option")?.mapNotNull { o ->
            val n = o.attr("value").toIntOrNull() ?: return@mapNotNull null
            n to o.text().replace(CH_PREFIX, "").trim().ifEmpty { "Chapter $n" }
        }.orEmpty().ifEmpty {
            listOf(1 to (doc.selectFirst("h2.chapter-title")?.text()?.trim() ?: title))
        }
        return Story(title, author, category, chapters)
    }

    fun parseChapter(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val body = doc.selectFirst("div.chapter-body") ?: return null
        body.select("script, ins, iframe").remove()
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        return Chapter(doc.selectFirst("h2.chapter-title")?.text()?.trim(), body.html(), plain)
    }
}
