package `in`.jphe.storyvox.source.scribblehub.parser

import `in`.jphe.storyvox.source.scribblehub.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Scribble Hub parsers (WordPress + the site's "wi_" theme). Selectors follow
 * FanFicFare's `adapter_scribblehubcom.py`; listing cards are
 * `div.search_main_box` on ranking, genre, latest and search pages alike.
 */
internal object ScribbleHubParser {

    /** Series reference: numeric id + URL slug (the slug is needed to rebuild URLs). */
    data class Ref(val seriesId: String, val slug: String)

    data class Card(
        val ref: Ref,
        val title: String,
        val author: String,
        val cover: String?,
        val description: String?,
        val genres: List<String>,
        val chapters: Int?,
    )

    data class ListResult(val cards: List<Card>, val hasNext: Boolean)

    data class Series(
        val title: String,
        val author: String,
        val cover: String?,
        val description: String?,
        val genres: List<String>,
        val tags: List<String>,
        val completed: Boolean,
    )

    data class Toc(val chapterId: String, val title: String, val order: Int?)

    data class Chapter(val title: String?, val html: String, val plain: String)

    private val SERIES = Regex("""/series/(\d+)/([^/?#]+)""")
    private val READ_CH = Regex("""/read/\d+-[^/]*/chapter/(\d+)""")
    private val DIGITS = Regex("""([\d,.]+)\s*(k)?""", RegexOption.IGNORE_CASE)

    fun seriesRef(href: String): Ref? = SERIES.find(href)?.let { Ref(it.groupValues[1], it.groupValues[2]) }

    fun parseList(html: String, baseUrl: String): ListResult {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = doc.select("div.search_main_box").mapNotNull(::card)
        return ListResult(cards, hasNext(doc))
    }

    private fun card(el: Element): Card? {
        val a = el.selectFirst(".search_title a[href*=/series/]") ?: el.selectFirst("a[href*=/series/]") ?: return null
        val ref = seriesRef(a.attr("abs:href").ifBlank { a.attr("href") }) ?: return null
        val body = el.selectFirst(".search_body")?.clone()
        body?.select(".search_title, .search_stats, .search_genre, .dots, .morelink, .testhide, script")?.remove()
        val chapters = el.select(".search_stats span, .search_stats .nl_stat").firstOrNull {
            it.attr("title").contains("Chapter", true) || it.text().contains("Chapters", true)
        }?.text()?.let { DIGITS.find(it)?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull() }
        return Card(
            ref = ref,
            title = a.text().trim().ifEmpty { return null },
            author = (el.selectFirst("span[title=Author], .a_un_st, a[href*=/profile/]")?.text()?.trim()).orEmpty(),
            cover = el.selectFirst(".search_img img")?.let { it.attr("abs:data-src").ifBlank { it.attr("abs:src") } }?.ifBlank { null },
            description = body?.let { HtmlText.plain(it) }?.ifBlank { null },
            genres = el.select("a.fic_genre").map { it.text().trim() }.filter { it.isNotEmpty() },
            chapters = chapters,
        )
    }

    private fun hasNext(doc: Document): Boolean =
        doc.select("a[rel=next], a.next, a.page-link.next, .simple-pagination a, .wp-pagenavi a, a.page-numbers")
            .any { val t = it.text().trim(); it.hasClass("next") || it.attr("rel") == "next" || t == "»" || t.startsWith("Next") }

    fun parseSeries(html: String, baseUrl: String): Series? {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.selectFirst("div.fic_title")?.text()?.trim()?.ifEmpty { null } ?: return null
        val completed = doc.select("span[title^=Last]").firstOrNull()?.text()?.contains("Completed", true) == true
        return Series(
            title = title,
            author = doc.selectFirst("span.auth_name_fic")?.text()?.trim().orEmpty(),
            cover = doc.selectFirst("div.fic_image img")?.attr("abs:src")?.ifBlank { null },
            description = doc.selectFirst("div.wi_fic_desc")?.let { HtmlText.plain(it) }?.ifBlank { null },
            genres = doc.select("a.fic_genre").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            tags = doc.select("span.wi_fic_showtags_inner a").map { it.text().trim() }.filter { it.isNotEmpty() },
            completed = completed,
        )
    }

    /**
     * Parse the `wi_getreleases_pagination` admin-ajax fragment. The site
     * lists newest first; each `li.toc_w` carries an `order` attribute we use
     * to put chapters in reading order (falling back to reversed DOM order).
     */
    fun parseToc(fragment: String, baseUrl: String): List<Toc> {
        val doc = Jsoup.parseBodyFragment(fragment, baseUrl)
        val rows = doc.select("a.toc_a").mapNotNull { a ->
            val id = READ_CH.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            val order = a.closest("li")?.attr("order")?.toIntOrNull()
            Toc(id, a.text().trim().ifEmpty { "Chapter" }, order)
        }.distinctBy { it.chapterId }
        return if (rows.isNotEmpty() && rows.all { it.order != null }) rows.sortedBy { it.order } else rows.reversed()
    }

    fun parseChapter(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val body = doc.getElementById("chp_raw") ?: return null
        // Author's notes are kept but moved out of the narrated text.
        body.select("div.wi_authornotes, script, ins, .modern-footnotes-footnote__note").remove()
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        return Chapter(doc.selectFirst("div.chapter-title")?.text()?.trim(), body.html(), plain)
    }
}
