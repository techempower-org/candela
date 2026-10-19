package `in`.jphe.storyvox.source.fimfiction.parser

import `in`.jphe.storyvox.source.fimfiction.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * FimFiction HTML parsers: the `/stories` listing (browse + search) and the
 * `/chapters/download/<id>/html` chapter export.
 *
 * The listing selectors target the current `story-card` markup with a
 * last-resort fallback that groups bare `/story/<id>/<slug>` anchors, so a
 * class rename degrades to sparse cards instead of an empty page. Browse is
 * served without the `view_mature` cookie, so lists are the site's default
 * (non-mature) window — the same family-facing posture as FanFiction.net.
 */
internal object FimHtmlParser {

    data class Card(
        val storyId: String,
        val title: String,
        val author: String,
        val cover: String?,
        val description: String?,
        val tags: List<String>,
    )

    data class ListResult(val cards: List<Card>, val hasNext: Boolean)

    data class Chapter(val title: String?, val html: String, val plain: String)

    private val STORY_HREF = Regex("""/story/(\d+)(?:/|$)""")

    fun storyIdOf(href: String): String? = STORY_HREF.find(href)?.groupValues?.get(1)

    fun parseList(html: String, baseUrl: String): ListResult {
        val doc = Jsoup.parse(html, baseUrl)
        val cards = doc.select("div.story-card, div.story_container").mapNotNull(::card)
            .ifEmpty { fallbackCards(doc) }
        val hasNext = doc.select("a[rel=next], .page_list a, ul.page-list a, a.next-page").any {
            val t = it.text().trim()
            it.attr("rel") == "next" || t == "»" || t.startsWith("Next") || it.hasClass("next-page")
        }
        return ListResult(cards, hasNext)
    }

    private fun card(el: Element): Card? {
        val a = el.selectFirst("a.story-card__title, a.story_name")
            ?: el.select("a[href~=/story/\\d+]").firstOrNull { it.text().isNotBlank() }
            ?: return null
        val id = storyIdOf(a.attr("href")) ?: return null
        val title = a.text().trim().ifEmpty { return null }
        return Card(
            storyId = id,
            title = title,
            author = el.selectFirst(".story-card__author a, .author a, a[href*=/user/]")?.text()?.trim().orEmpty(),
            cover = el.selectFirst("img")?.let { img ->
                img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
            }?.ifBlank { null },
            description = el.selectFirst(".story-card__description, .description, .short_description")
                ?.let { HtmlText.plain(it) }?.ifBlank { null },
            tags = el.select("a.tag-genre, .story-card__tags a, ul.story-tags a")
                .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
        )
    }

    /** Class-rename safety net: one sparse card per distinct `/story/<id>/…` title anchor. */
    private fun fallbackCards(doc: Element): List<Card> =
        doc.select("a[href~=/story/\\d+/]")
            .filter { it.text().isNotBlank() }
            .mapNotNull { a -> storyIdOf(a.attr("href"))?.let { it to a.text().trim() } }
            .distinctBy { it.first }
            .map { (id, title) -> Card(id, title, "", null, null, emptyList()) }

    /**
     * The chapter export page is a minimal document: a heading, then the
     * chapter body as sibling paragraphs. Strip the chrome, keep the rest.
     */
    fun parseChapterDownload(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val body = doc.body()
        body.select("script, style, nav, header, footer").remove()
        val heading = body.selectFirst("h1, h2, h3")
        val title = heading?.text()?.trim()?.ifEmpty { null }
        heading?.remove()
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        return Chapter(title, body.html(), plain)
    }
}
