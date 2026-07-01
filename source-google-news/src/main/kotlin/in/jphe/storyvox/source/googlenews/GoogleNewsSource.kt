package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.RouteMatch
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.UrlMatcher
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.googlenews.article.ArticleResolver
import `in`.jphe.storyvox.source.googlenews.net.GoogleNewsApi
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsFeed
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsItem
import `in`.jphe.storyvox.source.googlenews.parse.GoogleNewsParser
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1238 — Google News as a fiction backend ("like the Chrome
 * new-tab feed").
 *
 * **Shape.** Google News exposes no per-article fetch-by-id endpoint and
 * obfuscates article links behind `CBMi…` redirects, so the storyvox
 * "fiction" grain is the **section feed**, not the article (see
 * [GoogleNewsSections] and the issue body). [popular] surfaces Top
 * stories + the 8 topic sections as cards; opening one lists the section
 * feed's current stories as chapters; each chapter narrates a headline +
 * publisher + related-coverage digest. This mirrors the `:source-rss`
 * feed→fiction / item→chapter pattern and keeps every fiction
 * rebuildable from its id alone (so `googlenews` is intentionally NOT in
 * [SourceIds.idNeedsSourceUrlToRebuild]).
 *
 * **Search.** A query becomes a single `googlenews:search:<q>` fiction
 * whose chapters are the search-feed results — "play me a briefing on
 * <q>".
 *
 * **Full article text** is out of scope for v1: it would require
 * decoding the redirect via a fragile internal RPC. The [articleResolver]
 * seam (a no-op today) is where that lands as a follow-up; until then the
 * digest body is the honest, always-available content.
 */
@SourcePlugin(
    id = SourceIds.GOOGLE_NEWS,
    displayName = "Google News",
    // General-interest news with no token/onboarding — surface it on
    // fresh installs (same discoverability posture as Hacker News #436).
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Top stories, topic sections & search · headlines and related coverage as listenable briefings",
    sourceUrl = "https://news.google.com",
    generateRouting = true,
)
@Singleton
internal class GoogleNewsSource @Inject constructor(
    private val api: GoogleNewsApi,
    private val articleResolver: ArticleResolver,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.GOOGLE_NEWS
    override val displayName: String = "Google News"

    // ─── browse ──────────────────────────────────────────────────────────

    /** Browse landing: Top stories + the 8 topic sections as cards. */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(
            ListPage(
                items = GoogleNewsSections.catalog().map { sectionSummary(it.fictionId, it.title) },
                page = 1,
                hasNext = false,
            ),
        )

    /** No distinct "latest" surface — the section cards already are the
     *  live feed. Empty keeps the tab inert if a caller mounts it. */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page = 1, hasNext = false))

    /** Topics are surfaced as Browse cards via [popular], not as a genre
     *  taxonomy — empty, matching the `:source-rss` / Hacker News pattern. */
    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    /** A free-text search becomes a single search-results fiction. */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(emptyList(), page = 1, hasNext = false))
        }
        val fictionId = GoogleNewsSections.searchFictionId(term)
        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.GOOGLE_NEWS,
            title = "“$term” — Google News",
            author = GOOGLE_NEWS,
            description = "Google News search results for “$term”",
            status = FictionStatus.ONGOING,
        )
        return FictionResult.Success(ListPage(listOf(summary), page = 1, hasNext = false))
    }

    // ─── detail ──────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val feedUrl = GoogleNewsSections.feedUrlFor(fictionId)
            ?: return FictionResult.NotFound("Not a Google News section: $fictionId")

        val feed = when (val r = fetchAndParse(feedUrl)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val chapters = feed.items.mapIndexed { idx, item -> item.toChapterInfo(idx, fictionId) }
        val title = GoogleNewsSections.titleFor(fictionId).ifBlank { feed.title.ifBlank { "Google News" } }
        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.GOOGLE_NEWS,
            title = title,
            author = GOOGLE_NEWS,
            description = "Google News · ${chapters.size} stories · updated continuously",
            status = FictionStatus.ONGOING,
            chapterCount = chapters.size,
        )
        val lastUpdatedAt = feed.items.mapNotNull { it.publishedAtEpochMs }.maxOrNull()
        return FictionResult.Success(
            FictionDetail(summary = summary, chapters = chapters, lastUpdatedAt = lastUpdatedAt),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val feedUrl = GoogleNewsSections.feedUrlFor(fictionId)
            ?: return FictionResult.NotFound("Not a Google News section: $fictionId")

        val feed = when (val r = fetchAndParse(feedUrl)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val (idx, item) = feed.items.withIndex()
            .firstOrNull { (_, it) -> it.toChapterId(fictionId) == chapterId }
            ?: return FictionResult.NotFound("Story no longer in the feed: $chapterId")

        // v1: the resolver is a no-op and returns null, so we narrate the
        // digest. When a future resolver recovers the article text, it is
        // prepended ahead of the attribution + related coverage.
        val articleText = articleResolver.resolve(item)
        val plain = composePlainBody(item, articleText)
        return FictionResult.Success(
            ChapterContent(
                info = item.toChapterInfo(idx, fictionId),
                htmlBody = composeHtmlBody(plain),
                plainBody = plain,
            ),
        )
    }

    // ─── auth / follow (not applicable) ───────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── url matching (#472) ──────────────────────────────────────────────

    /**
     * Routes pasted Google News *section* URLs (the home page or a
     * `…/topic/<KEY>` headlines link) to the matching section fiction.
     * Obfuscated `…/articles/CBMi…` links are deliberately NOT claimed —
     * storyvox can't resolve them to content, so letting them fall
     * through is more honest than routing to the wrong place.
     */
    override fun matchUrl(url: String): RouteMatch? {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", true) && !trimmed.startsWith("https://", true)) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.")?.lowercase() ?: return null
        if (host != "news.google.com") return null

        val path = uri.path.orEmpty()
        if (path.contains("/articles/", ignoreCase = true)) return null // unresolvable redirect

        val topicKey = TOPIC_PATH.find(path)?.groupValues?.get(1)?.uppercase()
        val fictionId = when {
            topicKey != null && GoogleNewsSections.TOPICS.any { it.first == topicKey } ->
                "${SourceIds.GOOGLE_NEWS}:topic:$topicKey"
            else -> GoogleNewsSections.TOP_ID
        }
        return RouteMatch(
            sourceId = SourceIds.GOOGLE_NEWS,
            fictionId = fictionId,
            confidence = 0.85f,
            label = "Google News · ${GoogleNewsSections.titleFor(fictionId)}",
        )
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun fetchAndParse(feedUrl: String): FictionResult<GoogleNewsFeed> =
        when (val r = api.fetchFeed(feedUrl)) {
            is FictionResult.Success -> {
                val feed = GoogleNewsParser.parse(r.value)
                if (feed.items.isEmpty()) {
                    FictionResult.NetworkError("Google News returned no stories")
                } else {
                    FictionResult.Success(feed)
                }
            }
            is FictionResult.Failure -> r
        }

    private fun sectionSummary(fictionId: String, title: String): FictionSummary =
        FictionSummary(
            id = fictionId,
            sourceId = SourceIds.GOOGLE_NEWS,
            title = title,
            author = GOOGLE_NEWS,
            description = "Google News · updated continuously",
            status = FictionStatus.ONGOING,
        )

    /** The TTS-facing body. v1 (no resolved article): headline, publisher
     *  attribution, then the related-coverage headlines Google clustered
     *  with the story. */
    private fun composePlainBody(item: GoogleNewsItem, articleText: String?): String {
        val parts = mutableListOf<String>()
        parts += item.title
        val attribution = buildString {
            if (item.publisher.isNotBlank()) append(item.publisher)
        }
        if (attribution.isNotBlank()) parts += attribution
        if (!articleText.isNullOrBlank()) {
            parts += articleText
        } else if (item.relatedHeadlines.isNotEmpty()) {
            parts += "Related coverage:"
            item.relatedHeadlines.forEach { parts += "• $it" }
        }
        return parts.joinToString("\n\n")
    }

    private fun composeHtmlBody(plain: String): String =
        plain.split("\n\n").joinToString("") { para ->
            "<p>${escapeHtml(para).replace("\n", "<br/>")}</p>"
        }

    private companion object {
        const val GOOGLE_NEWS = "Google News"
        val TOPIC_PATH = Regex("/topic/([A-Za-z]+)", RegexOption.IGNORE_CASE)
    }
}

/** Stable chapter id for a story within its section fiction — the
 *  section id plus a hash of the item's `<guid>`, so it survives the
 *  feed reshuffling (stories that drop out simply 404; survivors keep
 *  their id). Mirrors `:source-rss`'s item→chapter id scheme. */
internal fun GoogleNewsItem.toChapterId(fictionId: String): String =
    "$fictionId::${guid.hashCode().toUInt().toString(16)}"

internal fun GoogleNewsItem.toChapterInfo(index: Int, fictionId: String): ChapterInfo =
    ChapterInfo(
        id = toChapterId(fictionId),
        sourceChapterId = guid,
        index = index,
        title = title.ifBlank { "Story ${index + 1}" },
        publishedAt = publishedAtEpochMs,
    )

/** Minimal HTML escape for the htmlBody round-trip (reader view). */
private fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
