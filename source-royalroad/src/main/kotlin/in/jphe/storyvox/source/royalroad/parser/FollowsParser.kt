package `in`.jphe.storyvox.source.royalroad.parser

import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds
import `in`.jphe.storyvox.source.royalroad.model.extractFictionIdFromHref
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parses the logged-in user's Royal Road follows list.
 *
 * RR's `/profile/me/follows` endpoint redirects to the user's actual profile
 * URL when authed, or to `/home` when not. The follow rows render as the
 * same `div.fiction-list-item.row` cards used on browse pages, so we can
 * reuse [BrowseParser]'s row extraction shape.
 *
 * Returns [FollowsResult.NotAuthenticated] when the response is the home
 * redirect, otherwise a [ListPage] of summaries.
 */
internal object FollowsParser {

    sealed interface FollowsResult {
        data class Ok(val items: ListPage<FictionSummary>) : FollowsResult
        data object NotAuthenticated : FollowsResult
    }

    fun parse(html: String, finalUrl: String, currentPage: Int = 1): FollowsResult {
        if (looksUnauthed(finalUrl, html)) return FollowsResult.NotAuthenticated

        val doc = Jsoup.parse(html, RoyalRoadIds.BASE_URL)
        HoneypotFilter.strip(doc)

        val items = doc.select("div.fiction-list-item.row").mapNotNull(::parseRow)
        val hasNext = hasNextPage(doc, currentPage)
        return FollowsResult.Ok(ListPage(items = items, page = currentPage, hasNext = hasNext))
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        val pages = doc.select("ul.pagination li a[data-page]")
            .mapNotNull { it.attr("data-page").toIntOrNull() }
        return pages.any { it > currentPage }
    }

    private fun looksUnauthed(finalUrl: String, html: String): Boolean {
        val pathOnly = finalUrl.substringAfter(RoyalRoadIds.BASE_URL, "")
        if (pathOnly.startsWith("/home") || pathOnly.startsWith("/account/login")) return true
        // Defensive: if the body has the login form prominently, treat as unauthed.
        return html.contains("class=\"form-login-details\"") &&
            !html.contains("class=\"fiction-list-item")
    }

    private fun parseRow(row: Element): FictionSummary? {
        val titleAnchor = row.selectFirst("h2.fiction-title a") ?: return null
        val href = titleAnchor.attr("href")
        val fictionId = extractFictionIdFromHref(href) ?: return null
        val title = titleAnchor.text().trim().ifEmpty { return null }

        val cover = row.selectFirst("figure img")?.let { img ->
            // Issue #283 — prefer the lazy-load src attributes so we get
            // the real cover instead of the placeholder. See the kdoc
            // on BrowseParser.absoluteCoverUrl for the full story.
            val raw = listOf("data-src", "data-lazy-src", "src")
                .map { img.attr(it) }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
                ?: ""
            absoluteCoverUrl(raw)
        }

        val tags = row.select("a.fiction-tag").mapNotNull { tag ->
            tag.attr("href").substringAfter("tagsAdd=", "").substringBefore("&").trim()
                .takeIf { it.isNotEmpty() }
        }

        val description = row.selectFirst("[id^=description-]")?.text()?.trim()?.ifEmpty { null }

        return FictionSummary(
            id = fictionId,
            sourceId = RoyalRoadIds.SOURCE_ID,
            title = title,
            // Browse-row shape doesn't expose author; the detail-page
            // refresh fills it in when the user opens the fiction.
            author = "",
            coverUrl = cover,
            description = description,
            tags = tags,
            status = FictionStatus.ONGOING,
            chapterCount = null,
            rating = null,
        )
    }

    private fun absoluteCoverUrl(src: String): String? {
        if (src.isEmpty() || src.endsWith("/dist/img/nocover-new-min.png")) return null
        return when {
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "${RoyalRoadIds.BASE_URL}$src"
            else -> src
        }
    }
}
