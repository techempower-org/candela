package `in`.jphe.storyvox.source.googlenews.parse

import `in`.jphe.storyvox.data.text.htmlToInlineText
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Issue #1238 — Google News RSS 2.0 parser.
 *
 * Google News feeds are plain RSS 2.0 (`<rss><channel><item>…`), simpler
 * than the multi-dialect feeds `:source-rss` handles, so this uses the
 * JDK's `javax.xml` DOM builder rather than `android.util.Xml`. That
 * keeps the parser unit-testable on a vanilla JUnit runner (no
 * Robolectric) while still running on Android, where
 * `DocumentBuilderFactory` is part of the SDK.
 *
 * The parser is total: it never throws. Malformed input yields an empty
 * feed, and individual unparseable items are skipped — the source layer
 * surfaces an empty/failed result rather than crashing a Browse open.
 */
internal object GoogleNewsParser {

    fun parse(xml: String): GoogleNewsFeed {
        val doc = runCatching {
            val dbf = DocumentBuilderFactory.newInstance().apply {
                // XXE hardening — best-effort; Android's parser may not
                // support every feature, so each toggle is guarded.
                runCatching { setFeature(DISALLOW_DOCTYPE, true) }
                runCatching { setFeature(EXTERNAL_GENERAL_ENTITIES, false) }
                runCatching { setFeature(EXTERNAL_PARAMETER_ENTITIES, false) }
                isExpandEntityReferences = false
            }
            dbf.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull() ?: return EMPTY

        val channel = doc.getElementsByTagName("channel")
            .takeIf { it.length > 0 }
            ?.item(0) as? Element
            ?: return EMPTY

        val channelTitle = directChildText(channel, "title").orEmpty().trim()

        val itemNodes = channel.getElementsByTagName("item")
        val items = buildList {
            for (i in 0 until itemNodes.length) {
                (itemNodes.item(i) as? Element)?.let { parseItem(it) }?.let(::add)
            }
        }
        return GoogleNewsFeed(title = channelTitle, items = items)
    }

    private fun parseItem(item: Element): GoogleNewsItem? {
        val rawTitle = directChildText(item, "title").orEmpty().trim()
        if (rawTitle.isBlank()) return null

        val link = directChildText(item, "link").orEmpty().trim()
        val guid = directChildText(item, "guid").orEmpty().trim().ifBlank { link }
        if (guid.isBlank()) return null

        val sourceText = directChildText(item, "source").orEmpty().trim()
        val (headline, publisher) = splitTitle(rawTitle, sourceText)

        return GoogleNewsItem(
            title = headline,
            publisher = publisher,
            link = link,
            guid = guid,
            publishedAtEpochMs = parseRfc822(directChildText(item, "pubDate")),
            relatedHeadlines = relatedHeadlinesFrom(
                directChildText(item, "description").orEmpty(),
                exclude = headline,
            ),
        )
    }

    /**
     * Split Google's `"Headline - Publisher"` title. When the item
     * carries a `<source>` element we trust that for the publisher (and
     * strip a matching trailing suffix off the headline); otherwise we
     * split on the last `" - "`. A title with neither yields an empty
     * publisher rather than a wrong guess.
     */
    internal fun splitTitle(rawTitle: String, sourceText: String): Pair<String, String> {
        if (sourceText.isNotBlank()) {
            val suffix = " - $sourceText"
            val headline =
                if (rawTitle.endsWith(suffix)) rawTitle.dropLast(suffix.length).trim() else rawTitle
            return headline to sourceText
        }
        val idx = rawTitle.lastIndexOf(" - ")
        return if (idx > 0) {
            rawTitle.substring(0, idx).trim() to rawTitle.substring(idx + 3).trim()
        } else {
            rawTitle to ""
        }
    }

    /**
     * Pull related-coverage headlines from the description's `<ol>`.
     * Google clusters sibling articles as `<a>` links there; we keep
     * each anchor's text (tags stripped, entities decoded), drop the one
     * that repeats the main [exclude] headline, and cap the list. The
     * feed never carries article body text, so headlines are all there
     * is to surface.
     */
    internal fun relatedHeadlinesFrom(descriptionHtml: String, exclude: String): List<String> {
        if (descriptionHtml.isBlank()) return emptyList()
        return A_TAG.findAll(descriptionHtml)
            .map { it.groupValues[1].htmlToInlineText() }
            .filter { it.isNotBlank() && !it.equals(exclude, ignoreCase = true) }
            .distinct()
            .take(MAX_RELATED)
            .toList()
    }

    /** First immediate child element with [tag], or null. Avoids
     *  `getElementsByTagName`'s recursive descent (which would pull an
     *  item's `<title>` when asked for the channel's). */
    private fun directChildText(parent: Element, tag: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val n = children.item(i)
            if (n is Element && n.tagName == tag) return n.textContent
        }
        return null
    }

    private fun parseRfc822(date: String?): Long? {
        val d = date?.trim().orEmpty()
        if (d.isBlank()) return null
        for (pattern in DATE_PATTERNS) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }
            runCatching { fmt.parse(d)?.time }.getOrNull()?.let { return it }
        }
        return null
    }

    private const val MAX_RELATED = 6

    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"
    private const val EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities"
    private const val EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities"

    private val EMPTY = GoogleNewsFeed(title = "", items = emptyList())
    private val A_TAG = Regex("<a\\b[^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss zzz",
        "EEE, dd MMM yyyy HH:mm:ss Z",
    )
}
