package `in`.jphe.storyvox.source.arxiv

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
import `in`.jphe.storyvox.source.arxiv.net.ArxivAbstractParser
import `in`.jphe.storyvox.source.arxiv.net.ArxivApi
import `in`.jphe.storyvox.source.arxiv.net.ArxivAtomFeed
import `in`.jphe.storyvox.source.arxiv.net.ArxivFeedEntry
import `in`.jphe.storyvox.source.arxiv.net.formatAuthors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #378 — arXiv as a non-fiction long-form backend (the "open-
 * access academic papers" surface). Second non-fiction shape after
 * Wikipedia (#377); same posture — read-only, no auth, machine-friendly
 * public API.
 *
 *  - Each arXiv **paper** is one fiction.
 *  - The fiction has **one chapter** ("Abstract") whose body is the
 *    title + author byline + subjects + comments + abstract paragraph,
 *    extracted from the paper's `arxiv.org/abs/<id>` HTML page.
 *
 * Skipping full-PDF text extraction is an explicit v1 scope cut from
 * #378's acceptance criteria — JP listens to "morning digest of new
 * AI papers" via the abstract alone. A follow-up issue will land full-
 * paper body extraction (the PDF → narratable-text pipeline is its
 * own substantial backend).
 *
 * ## Browse landing
 *
 * Popular / NewReleases / BestRated all collapse to "recent in
 * [ArxivApi.DEFAULT_CATEGORY]" — cs.AI for v1. arXiv exposes the same
 * underlying query for each (sorted by submittedDate desc); browse
 * tabs that don't fit (BestRated has no analogue on arXiv — papers
 * aren't ranked) reuse the recent listing rather than show an empty
 * picker. Search uses the `all:<term>` query.
 *
 * ## Fiction IDs
 *
 * `arxiv:<arxiv-id>` where `<arxiv-id>` is arXiv's canonical paper id
 * stripped of any version suffix (`v1`, `v2`, ...). Examples:
 *  - `arxiv:2401.12345` — post-2007 new-style ids
 *  - `arxiv:cs/0703021` — pre-2007 archive-style ids with embedded slash
 *
 * ## Auth-gated calls
 *
 * arXiv has no per-user follow concept storyvox can push to. The
 * `followsList` / `setFollowed` overrides return empty/Success so the
 * Follow button stays hidden on the FictionDetail screen (`supportsFollow
 * = false` on the [SourcePlugin] annotation).
 */
@SourcePlugin(
    id = SourceIds.ARXIV,
    displayName = "arXiv",
    // #436 — fresh-install discoverability: chip on by default.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Open-access academic papers · abstract + author byline as single-chapter fictions · default cs.AI",
    sourceUrl = "https://arxiv.org",
    // #1482 — chipLabel omitted: "arXiv" chip == displayName.
    searchHint = "Search arXiv — open-access academic papers",
)
@Singleton
internal class ArxivSource @Inject constructor(
    private val api: ArxivApi,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.ARXIV
    override val displayName: String = "arXiv"

    /** Issue #472 — claim `arxiv.org/abs/<id>` and `arxiv.org/pdf/<id>`
     *  URLs at high confidence. Anchored on the host so an outbound
     *  link to arxiv from a blog post doesn't accidentally claim a
     *  pasted blog URL. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = ARXIV_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val arxivId = m.groupValues[1]
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.ARXIV,
            fictionId = arxivFictionId(arxivId),
            confidence = 0.95f,
            label = "arXiv paper",
        )
    }

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Select(
            key = "category",
            label = "Category",
            options = listOf(
                "cs.AI", "cs.CL", "cs.LG", "cs.CV", "cs.RO",
                "cs.NE", "cs.SE", "cs.DC", "cs.CR", "cs.DB",
                "stat.ML", "math.OC",
            ),
        ),
        FilterDimension.DateRange(),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        var q = base
        state.stringVal("category")?.takeIf { it.isNotBlank() }?.let { cat ->
            q = q.copy(genres = q.genres + cat)
        }
        state.stringVal("dateRange")?.takeIf { it != "any" }?.let {
            q = q.copy(orderBy = SearchOrder.LAST_UPDATE)
        }
        return q
    }

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val pageIdx = (page - 1).coerceAtLeast(0)
        val start = pageIdx * ArxivApi.DEFAULT_PAGE_SIZE
        return api.recent(start = start).map { feed ->
            feed.toListPage(page)
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // arXiv's "submittedDate desc" is the same surface that powers
        // [popular] — there's no distinct "trending" vs "newest" on the
        // public API. Aliasing avoids a redundant fetch for the
        // NewReleases tab.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        // Genre = arXiv category code (`cs.AI`, `cs.CL`, ...). Empty
        // genre falls back to the default category for the bare
        // "browse" hit path.
        val cat = genre.ifBlank { ArxivApi.DEFAULT_CATEGORY }
        val pageIdx = (page - 1).coerceAtLeast(0)
        val start = pageIdx * ArxivApi.DEFAULT_PAGE_SIZE
        return api.recent(category = cat, start = start).map { feed ->
            feed.toListPage(page)
        }
    }

    override suspend fun genres(): FictionResult<List<String>> =
        // Curated subset of the arXiv category tree. The full list is
        // huge (200+ codes across math/physics/cs/q-bio/...); for v1 we
        // surface the CS subset since the default landing is cs.AI and
        // the JP-shaped use case is AI/ML papers. A v2 follow-up can
        // add a Settings-side picker that drives a richer list.
        FictionResult.Success(
            listOf(
                "cs.AI", "cs.CL", "cs.LG", "cs.CV", "cs.RO",
                "cs.NE", "cs.SE", "cs.DC", "cs.CR", "cs.DB",
                "stat.ML", "math.OC",
            )
        )

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        if (term.isEmpty()) return popular(1)
        val pageIdx = (query.page - 1).coerceAtLeast(0)
        val start = pageIdx * ArxivApi.DEFAULT_PAGE_SIZE
        return api.search(term = term, start = start).map { feed ->
            feed.toListPage(query.page)
        }
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val arxivId = fictionId.toArxivId()
            ?: return FictionResult.NotFound("arXiv fiction id not recognized: $fictionId")
        // Two paths: query API (Atom — structured) and abstract page
        // (HTML — has the comments field the Atom doesn't surface). We
        // prefer the Atom for the summary card (canonical metadata),
        // then layer the abstract-page parse into the chapter body in
        // [chapter]. fictionDetail doesn't fetch chapter bodies — same
        // contract as the other backends.
        return when (val byId = api.byId(arxivId)) {
            is FictionResult.Success -> {
                val entry = byId.value.entries.firstOrNull()
                    ?: return FictionResult.NotFound("arXiv paper not found: $arxivId")
                val summary = entry.toSummary(fictionId)
                val chapterInfo = ChapterInfo(
                    id = chapterIdFor(fictionId),
                    sourceChapterId = "abstract",
                    index = 0,
                    title = "Abstract",
                    publishedAt = null,
                )
                FictionResult.Success(
                    FictionDetail(
                        summary = summary.copy(chapterCount = 1),
                        chapters = listOf(chapterInfo),
                    ),
                )
            }
            is FictionResult.Failure -> byId
        }
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val arxivId = fictionId.toArxivId()
            ?: return FictionResult.NotFound("arXiv fiction id not recognized: $fictionId")
        if (!chapterId.endsWith("::abstract")) {
            return FictionResult.NotFound("arXiv chapter id not recognized: $chapterId")
        }
        return when (val r = api.absPage(arxivId)) {
            is FictionResult.Success -> {
                val parsed = ArxivAbstractParser.parse(r.value)
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "abstract",
                    index = 0,
                    title = "Abstract",
                )
                val htmlBody = ArxivAbstractParser.toChapterHtml(parsed)
                val plainBody = ArxivAbstractParser.toChapterPlain(parsed)
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = htmlBody,
                        plainBody = plainBody,
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // arXiv has no account-side follow concept storyvox can sync
        // with — the public API is read-only and anonymous.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Issue #472 — arXiv URL pattern for the magic-link resolver.
 *  Matches `arxiv.org/abs/<id>` (the canonical landing page) and
 *  `arxiv.org/pdf/<id>` (PDF direct link). The id captures both the
 *  new-style `2401.12345` and old-style `cs/0501001` forms. */
internal val ARXIV_URL_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?arxiv\.org/(?:abs|pdf)/([\w./-]+?)(?:v\d+)?(?:\.pdf)?(?:/.*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Compose a stable arXiv fiction id from the bare arxiv-id. */
internal fun arxivFictionId(arxivId: String): String =
    "arxiv:" + arxivId.trim()

/** Compose the canonical chapter id — every arXiv paper has exactly one
 *  chapter ("Abstract") in v1, so the suffix is fixed. */
internal fun chapterIdFor(fictionId: String): String =
    "$fictionId::abstract"

/** Strip the `arxiv:` prefix; returns null on ids that don't carry it. */
internal fun String.toArxivId(): String? =
    if (startsWith("arxiv:")) removePrefix("arxiv:").substringBefore("::")
    else null

private fun ArxivAtomFeed.toListPage(page: Int): ListPage<FictionSummary> {
    val items = entries.map { it.toSummary() }
    // arXiv returns whatever's left within the requested window. If we
    // got a full page the user can keep scrolling; if it short-returned
    // we're at the tail. Cheap-enough heuristic — opensearch:totalResults
    // is exposed in [ArxivAtomFeed.totalResults] but isn't always
    // populated, so the size-based check is the reliable signal.
    val hasNext = items.size >= ArxivApi.DEFAULT_PAGE_SIZE
    return ListPage(items = items, page = page, hasNext = hasNext)
}

internal fun ArxivFeedEntry.toSummary(
    fictionId: String = arxivFictionId(arxivId),
): FictionSummary = FictionSummary(
    id = fictionId,
    sourceId = SourceIds.ARXIV,
    title = title.ifBlank { arxivId },
    author = formatAuthors(authors).ifBlank { "arXiv" },
    description = summary.ifBlank { null },
    tags = categories,
    status = FictionStatus.COMPLETED,
    chapterCount = 1,
)
