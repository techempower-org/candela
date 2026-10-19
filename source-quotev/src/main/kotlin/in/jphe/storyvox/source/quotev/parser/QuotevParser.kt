package `in`.jphe.storyvox.source.quotev.parser

import `in`.jphe.storyvox.source.quotev.HtmlText
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Quotev parsers. UNVERIFIED AGAINST THE LIVE SITE: Quotev fronts every page
 * (any user agent) with its own JS wall, which `QuotevApi` maps to
 * `FictionResult.Cloudflare`, so these selectors could only be written from
 * FanFicFare's quotev adapter and are deliberately generic — anchors matched
 * by URL shape (`/story/<id>[/<slug>[/<n>]]`) rather than by class names,
 * with id/first-heading fallbacks. Verify on a device once a WebView
 * clearance path exists, and tighten then.
 */
internal object QuotevParser {

    data class Ref(val storyId: String, val slug: String)

    data class Card(val ref: Ref, val title: String)

    data class Story(
        val title: String,
        val author: String,
        val description: String?,
        val chapterNumbers: List<Int>,
    )

    data class Chapter(val title: String?, val html: String, val plain: String)

    private val STORY_HREF = Regex("""/story/(\d+)(?:/([^/?#]*))?""")
    private val CHAPTER_HREF = Regex("""/story/\d+/[^/?#]*/(\d+)(?:[/?#]|$)""")

    fun storyRef(href: String): Ref? = STORY_HREF.find(href)?.let {
        Ref(it.groupValues[1], it.groupValues[2].ifEmpty { "x" })
    }

    /** Any listing page: one card per distinct `/story/<id>` anchor with text. */
    fun parseList(html: String, baseUrl: String): List<Card> =
        Jsoup.parse(html, baseUrl)
            .select("a[href*=/story/]")
            .asSequence()
            .filter { it.text().isNotBlank() }
            .mapNotNull { a -> storyRef(a.attr("href"))?.let { Card(it, a.text().trim()) } }
            .distinctBy { it.ref.storyId }
            .toList()

    fun parseStory(html: String, baseUrl: String): Story? {
        val doc = Jsoup.parse(html, baseUrl)
        val title = doc.select("h1").firstOrNull { it.text().isNotBlank() }?.text()?.trim()
            ?: doc.title().substringBefore(" - Quotev").trim().ifEmpty { null }
            ?: return null
        val chapters = doc.select("a[href*=/story/]")
            .mapNotNull { CHAPTER_HREF.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()
        return Story(
            title = title,
            author = doc.selectFirst("a[href^=/][rel=author], .author a, a.q_un")?.text()?.trim().orEmpty(),
            description = doc.selectFirst("meta[name=description]")?.attr("content")?.trim()?.ifEmpty { null },
            chapterNumbers = chapters.ifEmpty { listOf(1) },
        )
    }

    fun parseChapter(html: String, baseUrl: String): Chapter? {
        val doc = Jsoup.parse(html, baseUrl)
        val body = contentRoot(doc) ?: return null
        body.select("script, style, ins, iframe, form, nav").remove()
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        return Chapter(
            doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null },
            body.html(),
            plain,
        )
    }

    private fun contentRoot(doc: Document): Element? =
        doc.getElementById("rescontent")
            ?: doc.selectFirst("article")
            ?: doc.selectFirst("#result, .result")
}
