package `in`.jphe.storyvox.source.livejournal.parser

import `in`.jphe.storyvox.source.livejournal.HtmlText
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Parses a LiveJournal RSS 2.0 feed (`<journal>.livejournal.com/data/rss`,
 * optionally `?tag=`). LJ puts each public post's full HTML in
 * `<description>`, so the feed alone is enough to narrate posts. The feed is
 * capped at the 25 most recent entries — an LJ server limit.
 */
internal object LjRssParser {

    data class Post(
        val itemId: String,
        val title: String,
        val link: String,
        val publishedAt: Long?,
        val html: String,
        val plain: String,
    )

    data class Feed(val journal: String, val title: String, val description: String?, val posts: List<Post>)

    private val ITEM_ID = Regex("""/(\d+)\.html""")

    fun parse(xml: String): Feed? {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val channel = doc.selectFirst("channel") ?: return null
        val journal = channel.selectFirst("lj|journal")?.text()?.trim()
            ?: channel.getElementsByTag("lj:journal").firstOrNull()?.text()?.trim()
            ?: ""
        val posts = channel.select("item").mapNotNull { item ->
            val link = item.selectFirst("link")?.text()?.trim().orEmpty()
            val id = ITEM_ID.find(link)?.groupValues?.get(1) ?: return@mapNotNull null
            // <description> holds escaped HTML; the XML parser hands it back unescaped.
            val body = Jsoup.parseBodyFragment(item.selectFirst("description")?.text().orEmpty()).body()
            body.select("script, iframe, .ljcut-link").remove()
            val plain = HtmlText.plain(body)
            Post(
                itemId = id,
                title = item.selectFirst("title")?.text()?.trim().orEmpty()
                    .ifEmpty { plain.take(60).substringBefore('\n').ifEmpty { "Post $id" } },
                link = link,
                publishedAt = item.selectFirst("pubDate")?.text()?.let(::rfc1123),
                html = body.html(),
                plain = plain,
            )
        }
        return Feed(
            journal = journal,
            title = channel.children().firstOrNull { it.tagName() == "title" }?.text()?.trim().orEmpty().ifEmpty { journal },
            description = channel.children().firstOrNull { it.tagName() == "description" }?.text()?.trim()?.ifEmpty { null },
            posts = posts.reversed(), // feed is newest-first; chapters read oldest-first
        )
    }

    private fun rfc1123(s: String): Long? =
        runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
}
