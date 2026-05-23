package `in`.jphe.storyvox.source.hackernews

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchOrder
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.hackernews.net.AlgoliaHit
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi.Companion.MAX_COMMENTS
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi.Companion.MAX_COMMENT_DEPTH
import `in`.jphe.storyvox.source.hackernews.net.HackerNewsApi.Companion.TOP_STORIES_LIMIT
import `in`.jphe.storyvox.source.hackernews.net.HnItem
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #379 — Hacker News as a fiction backend.
 *
 * Shape: each HN story is one fiction with exactly one chapter. The
 * chapter's body is either the story's own `text` field (Ask HN /
 * Show HN posts have inline text) or a synthetic narration that
 * reads the title, the linked URL, and the top-of-thread comments
 * (link-type stories).
 *
 * That single-chapter shape matches the way listeners actually
 * consume HN — you don't bookmark a thread and come back to chapter
 * 3, you listen to one story's discussion as a self-contained audio
 * episode. Multi-chapter would force a fake split that doesn't
 * exist in the underlying content.
 *
 * **Browse landing.** The v1 spec specifies three sub-lists per
 * Browse open: Top (first 50 of ~500 topstories), Ask HN
 * (askstories.json), Show HN (showstories.json). For the initial
 * contract this source's `popular()` returns Top only — the simplest
 * shape that maps cleanly to storyvox's existing per-tab paginator.
 * Ask / Show split as follow-ups (Ask becomes [latestUpdates] when
 * the spec for that arrives; Show as a third tab needs UI plumbing
 * before it's worth introducing).
 *
 * **Search.** HN's official Firebase API has no search; we use the
 * Algolia HN Search API (`hn.algolia.com/api/v1/search`) for free-
 * form text search over stories. Y Combinator hosts this index
 * specifically so apps don't have to scrape HTML.
 *
 * **Fiction id encoding.** `hackernews:<item-id>`. Same shape as
 * Royal Road / Gutenberg / etc. — keeps the cross-source UrlRouter
 * pattern intact.
 *
 * **Comment budgeting.** Link-type stories pull the top 20 comments
 * out of the front-page thread (the spec's "top 20") and walk each
 * kid subtree depth-first up to [MAX_COMMENT_DEPTH] levels deep,
 * capped at [MAX_COMMENTS] total comments rendered. The cap pair
 * means a runaway mega-thread doesn't push a single chapter into
 * megabytes of nested text; we surface the top-of-thread
 * conversation and stop.
 */
@SourcePlugin(
    id = SourceIds.HACKERNEWS,
    displayName = "Hacker News",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Top stories, Ask HN, Show HN · stories + top comments as single-chapter fictions · Algolia search",
    sourceUrl = "https://news.ycombinator.com",
)
@Singleton
internal class HackerNewsSource @Inject constructor(
    private val api: HackerNewsApi,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.HACKERNEWS
    override val displayName: String = "Hacker News"

    /** Issue #472 — `news.ycombinator.com/item?id=<id>` URL. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = HACKERNEWS_URL_PATTERN.matchEntire(url.trim()) ?: return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.HACKERNEWS,
            fictionId = "${SourceIds.HACKERNEWS}:${m.groupValues[1]}",
            confidence = 0.95f,
            label = "Hacker News thread",
        )
    }

    // ─── browse ────────────────────────────────────────────────────────

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("last_update", "Newest"),
                FilterDimension.SortOption("popularity", "Popular"),
            ),
        ),
        FilterDimension.Select(
            key = "category",
            label = "Category",
            options = listOf("story", "ask_hn", "show_hn", "front_page"),
        ),
        FilterDimension.DateRange(),
        FilterDimension.NumberRange(
            key = "minScore",
            label = "Minimum score",
            min = 0f,
            max = 500f,
            step = 10f,
            formatLabel = "points",
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "last_update" -> SearchOrder.LAST_UPDATE
                    "popularity" -> SearchOrder.POPULARITY
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        state.stringVal("category")?.takeIf { it.isNotBlank() }?.let { cat ->
            val composed = if (q.term.isBlank()) cat else "${q.term} $cat"
            q = q.copy(term = composed)
        }
        return q
    }

    /**
     * Top story landing. Fetches the ~500 ids from
     * `/v0/topstories.json`, slices the first 50, then hydrates each
     * id into a [FictionSummary] in parallel-ish fashion (sequential
     * `await` is fine — we're capped at 50 calls and the Firebase
     * endpoint is fast).
     *
     * Pagination semantics: HN top is a single ranked list, not a
     * cursor — there's no second page that's any more meaningful than
     * "the next 50 ids after position 50". We return `hasNext = false`
     * and rely on the fact that listeners reaching the bottom of the
     * top 50 are well-served by switching to Browse → Search or
     * coming back tomorrow.
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val ids = when (val r = api.topStoryIds()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val sliced = ids.take(TOP_STORIES_LIMIT)
        val summaries = sliced.mapNotNull { id -> hydrateSummary(id) }
        return FictionResult.Success(
            ListPage(items = summaries, page = 1, hasNext = false),
        )
    }

    /**
     * "Newest" surface — empty for now because the v1 spec scopes
     * Browse to Top only and the follow-ups (Ask HN, Show HN) will
     * each get their own paginator wiring. Returning an empty page
     * keeps the tab from breaking if BrowseScreen tries to mount it
     * before the follow-up land.
     */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    /**
     * HN doesn't carry per-story genre tags — Algolia indexes `_tags`
     * but those are item-type labels (`story`, `comment`, `poll`),
     * not subject genres. Returning empty per the genre-less pattern
     * already established by `:source-rss`.
     */
    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    /**
     * Free-form search via Algolia. Returns Algolia's ranked hits
     * mapped to [FictionSummary]. Algolia paginates with `page=N`
     * zero-indexed; storyvox uses 1-indexed pages — the api wrapper
     * translates.
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        val page = query.page.coerceAtLeast(1)
        val resp = when (val r = api.searchAlgolia(term, page)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val items = resp.hits.mapNotNull { it.toSummary() }
        return FictionResult.Success(
            ListPage(items = items, page = page, hasNext = page < resp.nbPages),
        )
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val itemId = parseHackerNewsId(fictionId)
            ?: return FictionResult.NotFound("Not a HN fictionId: $fictionId")

        val item = when (val r = api.item(itemId)) {
            is FictionResult.Success -> r.value
                ?: return FictionResult.NotFound("HN item $itemId is null/deleted")
            is FictionResult.Failure -> return r
        }
        val summary = item.toSummary() ?: return FictionResult.NotFound(
            "HN item $itemId is not a story (type=${item.type})",
        )
        val chapter = ChapterInfo(
            id = chapterIdFor(fictionId),
            sourceChapterId = item.id.toString(),
            index = 0,
            title = summary.title,
            publishedAt = item.time?.let { it * 1000L },
        )
        return FictionResult.Success(
            FictionDetail(
                summary = summary.copy(chapterCount = 1),
                chapters = listOf(chapter),
                lastUpdatedAt = item.time?.let { it * 1000L },
            ),
        )
    }

    /**
     * Build the chapter body. Two paths:
     *  - `text` field present (Ask HN / Show HN inline post) → strip
     *    HTML and return as the chapter body.
     *  - `url` field present (link submission) → compose a narration
     *    that reads the title and link, then walks the top 20
     *    comments breadth-first within the depth/total caps.
     *
     * If both are absent (a poll, a job posting, an item we shouldn't
     * have surfaced) we return NotFound.
     */
    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val itemId = parseHackerNewsId(fictionId)
            ?: return FictionResult.NotFound("Not a HN fictionId: $fictionId")

        val item = when (val r = api.item(itemId)) {
            is FictionResult.Success -> r.value
                ?: return FictionResult.NotFound("HN item $itemId is null/deleted")
            is FictionResult.Failure -> return r
        }

        val title = item.title.orEmpty().ifBlank { "Hacker News story $itemId" }
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = item.id.toString(),
            index = 0,
            title = title,
            publishedAt = item.time?.let { it * 1000L },
        )

        val body: String = when {
            !item.text.isNullOrBlank() -> item.text.toPlainText()
            !item.url.isNullOrBlank() -> renderLinkStoryBody(item)
            else -> return FictionResult.NotFound(
                "HN item $itemId has neither text nor url body",
            )
        }
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = "<p>${escapeHtml(body)}</p>",
                plainBody = body,
            ),
        )
    }

    /**
     * Compose the "link to <url> plus top comments" body. Walks the
     * item's `kids[]` top-down: for each top-level comment (capped at
     * 20 per the spec), descend up to [MAX_COMMENT_DEPTH] - 1 reply
     * levels and stop the whole walk when the running comment count
     * reaches [MAX_COMMENTS].
     */
    private suspend fun renderLinkStoryBody(item: HnItem): String {
        val parts = mutableListOf<String>()
        parts += "${item.title.orEmpty()} — link to ${item.url.orEmpty()}"
        // "Top 20" of the spec — but always inside the global
        // MAX_COMMENTS budget so a deeply-nested reply chain on
        // comment #1 doesn't starve the rest of the thread.
        val topComments = item.kids.take(20)
        val budget = CommentBudget(remaining = MAX_COMMENTS)
        for (kidId in topComments) {
            if (budget.remaining <= 0) break
            walkComment(kidId, depth = 1, parts = parts, budget = budget)
        }
        return parts.joinToString("\n\n")
    }

    private suspend fun walkComment(
        id: Long,
        depth: Int,
        parts: MutableList<String>,
        budget: CommentBudget,
    ) {
        if (depth > MAX_COMMENT_DEPTH) return
        if (budget.remaining <= 0) return
        val result = api.item(id)
        val item = (result as? FictionResult.Success)?.value ?: return
        if (item.deleted || item.dead) return
        if (item.type != "comment") return
        val text = item.text?.toPlainText().orEmpty()
        if (text.isBlank()) return
        val author = item.by ?: "anonymous"
        // Indent visually with em-dashes per depth so the body reads
        // like a threaded conversation aloud; TTS punctuation pause
        // tuning handles the prosody on top.
        val prefix = "—".repeat(depth) + " $author:"
        parts += "$prefix $text"
        budget.remaining -= 1
        if (depth + 1 > MAX_COMMENT_DEPTH) return
        for (childId in item.kids) {
            if (budget.remaining <= 0) break
            walkComment(childId, depth + 1, parts, budget)
        }
    }

    /** Mutable cell threaded through the comment walk so all branches
     *  share the same global count cap. Using a class lets the
     *  recursive calls observe each other's decrements without
     *  threading the value out as a return. */
    private class CommentBudget(var remaining: Int)

    // ─── auth-gated / follow ───────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Fetch one item id and translate the response into a summary.
     * Returns null when the item is deleted, isn't a story, or the
     * fetch fails — popular()/etc filter these out so the Browse
     * list doesn't include phantom rows for HN's nulled-out ids.
     */
    private suspend fun hydrateSummary(id: Long): FictionSummary? {
        val item = (api.item(id) as? FictionResult.Success)?.value ?: return null
        return item.toSummary()
    }

    private fun HnItem.toSummary(): FictionSummary? {
        if (deleted || dead) return null
        if (type != null && type != "story") return null
        val storyTitle = title?.trim().orEmpty()
        if (storyTitle.isBlank()) return null
        val descriptionParts = mutableListOf<String>()
        score?.let { descriptionParts += "$it points" }
        descendants?.let { descriptionParts += "$it comments" }
        url?.let { descriptionParts += it }
        return FictionSummary(
            id = "${SourceIds.HACKERNEWS}:$id",
            sourceId = SourceIds.HACKERNEWS,
            title = storyTitle,
            author = by ?: "anonymous",
            description = descriptionParts.joinToString(" · ").ifBlank { null },
            status = FictionStatus.COMPLETED,
            chapterCount = 1,
        )
    }

    private fun AlgoliaHit.toSummary(): FictionSummary? {
        val rawTitle = title?.trim().orEmpty()
        if (rawTitle.isBlank()) return null
        val descriptionParts = mutableListOf<String>()
        points?.let { descriptionParts += "$it points" }
        numComments?.let { descriptionParts += "$it comments" }
        url?.let { descriptionParts += it }
        return FictionSummary(
            id = "${SourceIds.HACKERNEWS}:$objectId",
            sourceId = SourceIds.HACKERNEWS,
            title = rawTitle,
            author = author ?: "anonymous",
            description = descriptionParts.joinToString(" · ").ifBlank { null },
            status = FictionStatus.COMPLETED,
            chapterCount = 1,
        )
    }
}

/** Issue #472 — Hacker News thread URL. */
internal val HACKERNEWS_URL_PATTERN: Regex = Regex(
    """^https?://news\.ycombinator\.com/item\?(?:[^=&]*=[^&]*&)*id=(\d+)(?:&.*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Parse the `hackernews:<id>` encoding. Returns null on malformed
 * input — calls return [FictionResult.NotFound] in that case.
 */
internal fun parseHackerNewsId(fictionId: String): Long? =
    fictionId.substringAfter("${SourceIds.HACKERNEWS}:", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toLongOrNull()

/** Stable single-chapter id so chapter lookups can route by item id
 *  alone. The `c0` suffix matches the "one chapter per fiction"
 *  shape — adding chapters per story isn't on the roadmap. */
internal fun chapterIdFor(fictionId: String): String = "${fictionId}::c0"

/**
 * HN renders comment / Ask bodies as a tiny HTML subset — paragraphs,
 * `<i>`, `<a>`, `<pre><code>`, line breaks. The TTS pipeline
 * normalizes plaintext on top of whatever we hand it; this strip
 * just removes the tags and decodes the basic entities so the engine
 * doesn't read out angle brackets and `&#x27;`.
 */
internal fun String.toPlainText(): String {
    val withoutTags = Regex("<[^>]+>").replace(this, " ")
    val decoded = withoutTags
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
    return decoded.replace(Regex("\\s+"), " ").trim()
}

/** Minimal HTML escape for the htmlBody round-trip. The reader view
 *  treats this body as a single `<p>` so newlines and ampersands
 *  need to be escaped to render correctly. */
internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
