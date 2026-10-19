package `in`.jphe.storyvox.source.foxaholic.parser

import `in`.jphe.storyvox.source.foxaholic.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parsers for Foxaholic, a WordPress site on the "Madara" manga/novel theme.
 * Madara markup is shared by hundreds of sites, so these selectors are the
 * theme's stable ones: `.page-item-detail` (archive cards),
 * `.c-tabs-item__content` (search cards), `.post-title`, `.summary__content`,
 * `li.wp-manga-chapter` (chapter rows), `.reading-content` (chapter text).
 */
internal object MadaraParser {

    data class Card(val slug: String, val title: String, val cover: String?, val description: String?, val genres: List<String>)
    data class ListResult(val cards: List<Card>, val hasNext: Boolean)
    data class Novel(
        val title: String,
        val author: String,
        val cover: String?,
        val description: String?,
        val genres: List<String>,
        val completed: Boolean,
    )
    /** [path] is the chapter path below the novel, e.g. `chapter-12` or `vol-1/chapter-3`. */
    data class ChapterRow(val path: String, val title: String)
    data class Chapter(val title: String?, val html: String, val plain: String)

    private val NOVEL = Regex("""/novel/([^/?#]+)/?""")

    fun novelSlug(href: String): String? = NOVEL.find(href)?.groupValues?.get(1)

    fun parseList(html: String, baseUrl: String): ListResult {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = (doc.select("div.page-item-detail") + doc.select("div.c-tabs-item__content")).mapNotNull(::card)
            .distinctBy { it.slug }
        return ListResult(cards, hasNext(doc))
    }

    private fun card(el: Element): Card? {
        val a = el.selectFirst(".post-title a[href*=/novel/]") ?: el.selectFirst("a[href*=/novel/]") ?: return null
        val slug = novelSlug(a.attr("href")) ?: return null
        val title = a.text().trim().ifEmpty { el.selectFirst("a[title]")?.attr("title")?.trim().orEmpty() }
        if (title.isEmpty()) return null
        return Card(
            slug = slug,
            title = title,
            cover = el.selectFirst("img")?.let(::imgSrc),
            description = el.selectFirst(".tab-summary .summary-content, .post-content")?.text()?.trim()?.ifEmpty { null },
            genres = el.select(".genres-content a, .mg_genres a").map { it.text().trim() }.filter { it.isNotEmpty() },
        )
    }

    private fun imgSrc(img: Element): String? =
        listOf("abs:data-src", "abs:data-lazy-src", "abs:src").map { img.attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }

    private fun hasNext(doc: Document): Boolean =
        doc.selectFirst("a.nextpostslink, a.next.page-numbers, .nav-previous a, a[rel=next]") != null

    fun parseNovel(html: String, baseUrl: String): Novel? {
        val doc = Jsoup.parse(html, baseUrl)
        val titleEl = doc.selectFirst(".post-title h1") ?: doc.selectFirst(".post-title h3") ?: return null
        titleEl.select("span").remove() // "HOT"/"NEW" badges
        val title = titleEl.text().trim().ifEmpty { return null }
        val status = doc.select(".post-status .post-content_item").firstOrNull {
            it.selectFirst(".summary-heading")?.text()?.contains("Status", true) == true
        }?.selectFirst(".summary-content")?.text().orEmpty()
        return Novel(
            title = title,
            author = doc.select(".author-content a").joinToString(", ") { it.text().trim() },
            cover = doc.selectFirst(".summary_image img")?.let(::imgSrc),
            description = (doc.selectFirst(".summary__content") ?: doc.selectFirst(".description-summary"))
                ?.let { HtmlText.plain(it) }?.ifBlank { null },
            genres = doc.select(".genres-content a").map { it.text().trim() }.filter { it.isNotEmpty() },
            completed = status.contains("Completed", true),
        )
    }

    /** Chapter rows from the `ajax/chapters/` fragment, returned in reading order. */
    fun parseChapterRows(fragment: String, novelSlug: String): List<ChapterRow> {
        val doc = Jsoup.parseBodyFragment(fragment)
        return doc.select("li.wp-manga-chapter").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val path = a.attr("href").substringAfter("/novel/$novelSlug/", "").trim('/')
            if (path.isEmpty()) return@mapNotNull null
            if (li.hasClass("premium") || li.selectFirst(".coin, .fa-lock") != null) return@mapNotNull null
            ChapterRow(path, a.ownText().trim().ifEmpty { a.text().trim() }.ifEmpty { path })
        }.distinctBy { it.path }.reversed() // Madara lists newest first
    }

    fun parseChapter(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val content = doc.selectFirst(".reading-content .text-left")
            ?: doc.selectFirst(".reading-content .entry-content")
            ?: doc.selectFirst(".reading-content")
            ?: return null
        content.select("script, ins, iframe, .adsbygoogle, .code-block, .ads, noscript").remove()
        val plain = HtmlText.plain(content)
        if (plain.isBlank()) return null
        val title = doc.selectFirst("#chapter-heading")?.text()?.trim()
            ?: doc.selectFirst("ol.breadcrumb li.active")?.text()?.trim()
        return Chapter(title, content.html(), plain)
    }
}
