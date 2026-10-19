package `in`.jphe.storyvox.source.spacebattles.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Parsers for XenForo 2 boards running the threadmarks add-on. Selectors
 * verified against live Questionable Questing markup (same templates on
 * SpaceBattles and Sufficient Velocity): thread rows are
 * `div.structItem--thread`, threadmark rows `div.structItem--threadmark`
 * with `data-content-author`/`data-content-date`, post bodies
 * `article[data-content=post-N] .bbWrapper`.
 */
internal object XfEngineParser {

    data class ThreadRow(
        val threadId: String,
        val title: String,
        val author: String,
        val replies: Int?,
        val lastPostAt: Long?,
    )

    data class ListResult(val threads: List<ThreadRow>, val hasNext: Boolean)

    data class Threadmark(
        val postId: String,
        val title: String,
        val author: String,
        val createdAt: Long?,
        val words: Long?,
    )

    data class ThreadmarksPage(
        val title: String?,
        val marks: List<Threadmark>,
        val totalWords: Long?,
        val hasNext: Boolean,
    )

    data class Post(val title: String?, val html: String, val plain: String)

    private val THREAD_HREF = Regex("""/threads/(?:[^/?#]*\.)?(\d+)(?:/|$|\?|#)""")
    private val POST_HREF = Regex("""/post-(\d+)""")
    private val PAGE_PARAM = Regex("""[?&]page=(\d+)""")
    private val KM_NUMBER = Regex("""([\d,.]+)\s*([km])?""", RegexOption.IGNORE_CASE)
    private val TOTAL_WORDS = Regex("""([\d,.]+[km]?)\s+words""", RegexOption.IGNORE_CASE)

    fun threadIdOf(href: String): String? = THREAD_HREF.find(href)?.groupValues?.get(1)

    /** "1.3k" → 1300, "751" → 751, "1.2m" → 1200000. */
    fun approxNumber(raw: String?): Long? {
        val m = KM_NUMBER.find(raw?.trim().orEmpty()) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase()) {
            "k" -> 1_000.0
            "m" -> 1_000_000.0
            else -> 1.0
        }
        return (n * mult).toLong()
    }

    /** A forum node's thread listing. Sticky rows (rules, indexes) are skipped. */
    fun parseForum(html: String, baseUrl: String): ListResult {
        val doc = Jsoup.parse(html, baseUrl)
        val rows = doc.select("div.structItem--thread").mapNotNull { row ->
            if (row.selectFirst(".structItem-status--sticky") != null) return@mapNotNull null
            if (row.parents().any { it.hasClass("structItemContainer-group--sticky") }) return@mapNotNull null
            val a = row.selectFirst(".structItem-title a[href*=/threads/]") ?: return@mapNotNull null
            val tid = threadIdOf(a.attr("href")) ?: return@mapNotNull null
            val title = a.text().trim().ifEmpty { return@mapNotNull null }
            ThreadRow(
                threadId = tid,
                title = title,
                author = row.attr("data-author").trim(),
                replies = row.selectFirst(".structItem-cell--meta dl dd")
                    ?.text()?.let { approxNumber(it)?.toInt() },
                lastPostAt = row.selectFirst(".structItem-cell--latest time[data-time]")
                    ?.attr("data-time")?.toLongOrNull()?.times(1000),
            )
        }
        return ListResult(rows, hasNextPage(doc))
    }

    /** The `/threads/<id>/threadmarks` index (one page of it). */
    fun parseThreadmarks(html: String, baseUrl: String): ThreadmarksPage {
        val doc = Jsoup.parse(html, baseUrl)
        val marks = doc.select("div.structItem--threadmark").mapNotNull { row ->
            val a = row.selectFirst(".structItem-title a[href*=post-]") ?: return@mapNotNull null
            val postId = POST_HREF.find(a.attr("href"))?.groupValues?.get(1) ?: return@mapNotNull null
            Threadmark(
                postId = postId,
                title = a.text().trim().ifEmpty { "Threadmark" },
                author = row.attr("data-content-author").trim(),
                createdAt = row.attr("data-content-date").toLongOrNull()?.times(1000),
                words = row.selectFirst(".structItem-cell--meta dd")?.text()?.let(::approxNumber),
            )
        }
        return ThreadmarksPage(
            title = title(doc),
            marks = marks,
            totalWords = TOTAL_WORDS.find(doc.text())?.groupValues?.get(1)?.let(::approxNumber),
            hasNext = hasNextPage(doc),
        )
    }

    /** Thread title with XenForo's prefix labels ([Archive] etc.) stripped. */
    fun title(doc: Document): String? {
        val h1 = doc.selectFirst("h1.p-title-value")?.clone() ?: return null
        h1.select("span.label, span.label-append").remove()
        return h1.text().trim().ifEmpty { null }
    }

    /** The thread starter, from the thread/threadmarks page header. */
    fun threadAuthor(html: String, baseUrl: String): String? =
        Jsoup.parse(html, baseUrl).selectFirst(".p-description a.username")?.text()?.trim()?.ifEmpty { null }

    /** One post's body, located by its `post-<id>` anchor on a thread page. */
    fun parsePost(html: String, baseUrl: String, postId: String): Post? {
        val doc = Jsoup.parse(html, baseUrl)
        val article = doc.selectFirst("article[data-content=post-$postId]") ?: return null
        val body = article.selectFirst("div.message-userContent div.bbWrapper")
            ?: article.selectFirst("div.bbWrapper")
            ?: return null
        body.select("script, .message-lastEdit, .bbCodeBlock--unfurl, .js-lbImage-icons").remove()
        val plain = HtmlText.plain(body)
        if (plain.isBlank()) return null
        val label = article.selectFirst(".message-cell--threadmark-header .threadmarkLabel")?.text()?.trim()
        return Post(label?.ifEmpty { null }, body.html(), plain)
    }

    private fun hasNextPage(doc: Document): Boolean {
        if (doc.selectFirst("a.pageNav-jump--next") != null) return true
        val current = doc.selectFirst("li.pageNav-page--current")?.text()?.trim()?.toIntOrNull()
            ?: return false
        return doc.select(".pageNav a[href]").any { a ->
            val n = PAGE_PARAM.find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""/page-(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            n != null && n > current
        }
    }
}
