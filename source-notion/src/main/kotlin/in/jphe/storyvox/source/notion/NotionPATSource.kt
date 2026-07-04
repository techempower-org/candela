package `in`.jphe.storyvox.source.notion

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
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionMode
import `in`.jphe.storyvox.source.notion.net.NotionApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #770 — private Notion workspace reader via PAT, split from the
 * old dual-mode [NotionSource]. No anonymous-mode code paths at all.
 *
 * Users supply an Internal Integration Token from
 * notion.so/my-integrations and share the database with that
 * integration. The token is stored encrypted in app config.
 */
@SourcePlugin(
    id = SourceIds.NOTION_PAT,
    displayName = "Notion",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Private Notion databases via Integration Token",
    sourceUrl = "https://www.notion.so",
    // #1482 — chipLabel omitted: "Notion" chip == displayName.
    searchHint = "Search your configured Notion database",
)
@Singleton
internal class NotionPATSource @Inject constructor(
    private val api: NotionApi,
    private val config: NotionConfig,
) : FictionSource {

    override val id: String = SourceIds.NOTION_PAT
    override val displayName: String = "Notion"

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Default"),
                FilterDimension.SortOption("last_update", "Newest"),
                FilterDimension.SortOption("title", "Title"),
            ),
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("sort")?.let { sortId ->
            q = q.copy(
                orderBy = when (sortId) {
                    "last_update" -> SearchOrder.LAST_UPDATE
                    "title" -> SearchOrder.TITLE
                    else -> SearchOrder.RELEVANCE
                },
            )
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.mode != NotionMode.OFFICIAL_PAT || state.apiToken.isBlank()) {
            // Not configured — no token supplied. Return empty.
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        return api.queryDatabase(cursor = null, pageSize = 100).map { list ->
            ListPage(
                items = list.results.mapNotNull { it.toSummary(SourceIds.NOTION_PAT) },
                page = 1,
                hasNext = list.hasMore,
            )
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.mode != NotionMode.OFFICIAL_PAT || state.apiToken.isBlank()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        val term = query.term.trim().lowercase()
        return api.queryDatabase(cursor = null, pageSize = 100).map { list ->
            val items = list.results.mapNotNull { it.toSummary(SourceIds.NOTION_PAT) }
                .filter { summary ->
                    term.isEmpty() ||
                        summary.title.lowercase().contains(term) ||
                        summary.description?.lowercase()?.contains(term) == true
                }
            ListPage(items = items, page = 1, hasNext = false)
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val pageId = fictionId.toPageId()
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")
        // Two-call pattern: page metadata + block children.
        val pageResult = api.page(pageId)
        val page = when (pageResult) {
            is FictionResult.Success -> pageResult.value
            is FictionResult.Failure -> return pageResult
        }
        val blocksResult = api.pageBlocks(pageId)
        val blocks = when (blocksResult) {
            is FictionResult.Success -> blocksResult.value
            is FictionResult.Failure -> return blocksResult
        }
        // #1508 — child-page-aware chaptering. A page whose sub-pages are
        // the real chapters (JP's shorts DB) has no heading_1, so the old
        // splitOnHeading1 collapsed all 20 shorts into one "Intro" chapter.
        // planChapters emits a lead Section (the notes) + one Child per
        // sub-page; child titles come from the block inline, so listing
        // costs no extra fetches — only opening a child fetches its body.
        val chapters = planChapters(blocks).mapIndexed { idx, plan ->
            when (plan) {
                is NotionChapterPlan.Section -> ChapterInfo(
                    id = chapterIdFor(fictionId, plan.sectionIndex),
                    sourceChapterId = "section-${plan.sectionIndex}",
                    index = idx,
                    title = plan.title,
                    publishedAt = null,
                )
                is NotionChapterPlan.Child -> ChapterInfo(
                    id = childChapterIdFor(fictionId, plan.childPageId),
                    sourceChapterId = "child-${plan.childPageId}",
                    index = idx,
                    title = plan.title,
                    publishedAt = null,
                )
            }
        }
        val summary = page.toSummary(SourceIds.NOTION_PAT)?.copy(chapterCount = chapters.size)
            ?: FictionSummary(
                id = fictionId,
                sourceId = SourceIds.NOTION_PAT,
                title = "Untitled Notion page",
                author = "Notion",
                description = null,
                status = FictionStatus.ONGOING,
                chapterCount = chapters.size,
            )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val pageId = fictionId.toPageId()
            ?: return FictionResult.NotFound("Notion fiction id not recognized: $fictionId")

        // #1508 — a child-page chapter (`::child-<id>`) is its own sub-page:
        // fetch only THAT page's blocks and render the whole thing as one
        // chapter. Its title comes from the parent's block list.
        val childPageId = chapterId.substringAfterLast("::child-", "")
        if (childPageId.isNotBlank()) {
            return when (val r = api.pageBlocks(childPageId)) {
                is FictionResult.Success -> {
                    val title = childTitleFromParent(pageId, childPageId)
                    FictionResult.Success(
                        ChapterContent(
                            info = ChapterInfo(
                                id = chapterId,
                                sourceChapterId = "child-$childPageId",
                                index = 0,
                                title = title,
                            ),
                            htmlBody = r.value.joinToString("\n") { it.toHtml() },
                            plainBody = r.value.joinToString("\n\n") { it.toPlainText() }.trim(),
                        ),
                    )
                }
                is FictionResult.Failure -> r
            }
        }

        val sectionIndex = chapterId.substringAfterLast("::section-", "")
            .toIntOrNull()
            ?: return FictionResult.NotFound("Notion chapter id not recognized: $chapterId")
        return when (val r = api.pageBlocks(pageId)) {
            is FictionResult.Success -> {
                // In child-page mode section 0 is the non-child lead; in flat
                // mode fall back to heading_1 splitting.
                val sections = if (r.value.any { it.type == "child_page" }) {
                    listOf(NotionSection("Introduction", leadBlocks(r.value)))
                } else {
                    splitOnHeading1(r.value)
                }
                val section = sections.getOrNull(sectionIndex)
                    ?: return FictionResult.NotFound(
                        "Section $sectionIndex not found in Notion page $pageId",
                    )
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "section-$sectionIndex",
                    index = sectionIndex,
                    title = section.title,
                )
                val html = section.blocks.joinToString("\n") { it.toHtml() }
                val plain = section.blocks.joinToString("\n\n") { it.toPlainText() }
                    .trim()
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = html,
                        plainBody = plain,
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    /** Recover a child page's title from the parent's block list (the
     *  `child_page` block carries it inline). Falls back to "Untitled" if
     *  the parent fetch fails or the child isn't found — the body still
     *  renders. */
    private suspend fun childTitleFromParent(parentPageId: String, childPageId: String): String {
        val parent = api.pageBlocks(parentPageId)
        if (parent is FictionResult.Success) {
            parent.value.firstOrNull {
                it.type == "child_page" && it.id.replace("-", "") == childPageId
            }?.let { return childPageTitle(it) ?: "Untitled" }
        }
        return "Untitled"
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}
