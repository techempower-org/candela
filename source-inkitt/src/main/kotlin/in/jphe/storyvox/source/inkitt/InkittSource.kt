package `in`.jphe.storyvox.source.inkitt

import `in`.jphe.storyvox.data.source.FictionSource
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
import `in`.jphe.storyvox.source.inkitt.net.InkittApi
import `in`.jphe.storyvox.source.inkitt.parser.InkittParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Inkitt — original fiction. Ids: `inkitt:<storyId>`, chapters `…::<n>`
 * (chapter numbers; the chapter list rides the story page's server-rendered
 * `StoryChaptersList` props, next to a schema.org Book block).
 *
 * Only story and chapter pages are server-rendered — every listing surface
 * (genres, topics, user shelves) is client-rendered — so there is no browse:
 * popular()/latestUpdates() stay empty and search wants a pasted story URL
 * or id (the LiveJournal precedent). A plain-text search still probes the
 * SPA's own endpoint and parses whatever comes back tolerantly, so it fails
 * soft today and starts working if Inkitt's API surfaces.
 */
@SourcePlugin(
    id = "inkitt",
    displayName = "Inkitt",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Original fiction · anonymous, free chapters",
    sourceUrl = "https://www.inkitt.com",
    searchHint = "Paste an Inkitt story URL or id",
    iconName = "AutoStories",
)
@Singleton
internal class InkittSource @Inject constructor(
    private val api: InkittApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Inkitt"

    // No server-rendered listing surface to browse.
    override suspend fun popular(page: Int) = empty(page)
    override suspend fun latestUpdates(page: Int) = empty(page)
    override suspend fun byGenre(genre: String, page: Int) = empty(page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        if (term.isEmpty() || page > 1) return empty(page)
        // A pasted story URL or bare id resolves directly — the dependable path.
        storyId(term)?.let { sid ->
            return when (val d = fictionDetail("$ID:$sid")) {
                is FictionResult.Failure -> if (d is FictionResult.NotFound) empty(1) else d
                is FictionResult.Success -> FictionResult.Success(
                    ListPage(listOf(d.value.summary), 1, hasNext = false),
                )
            }
        }
        val q = URLEncoder.encode(term, "UTF-8")
        val r = api.get("/api/3/search/stories?q=$q", InkittApi.JSON)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        return FictionResult.Success(
            ListPage(
                items = InkittParser.parseSearchStories(r.value).map { (sid, title) ->
                    FictionSummary(
                        id = "$ID:$sid",
                        sourceId = ID,
                        title = title,
                        author = "Unknown",
                        companionSourceUrl = api.absolute("/stories/$sid"),
                    )
                },
                page = 1,
                hasNext = false,
            ),
        )
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val sid = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val r = api.get("/stories/$sid")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val s = InkittParser.parseStoryPage(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Inkitt: story $sid not found")
        val fid = "$ID:$sid"
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = s.title,
                    author = s.author.ifBlank { "Unknown" },
                    coverUrl = s.cover,
                    description = s.description,
                    tags = s.genres + listOfNotNull(s.contentRating),
                    status = if (s.ongoing) FictionStatus.ONGOING else FictionStatus.COMPLETED,
                    chapterCount = s.chapters.size.takeIf { it > 0 },
                    companionSourceUrl = api.absolute("/stories/$sid"),
                ),
                chapters = s.chapters.mapIndexed { i, c ->
                    ChapterInfo(id = "$fid::${c.number}", sourceChapterId = c.number.toString(), index = i, title = c.name)
                },
                genres = s.genres,
                lastUpdatedAt = s.updatedAt,
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val sid = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val n = chapterId.substringAfterLast("::").toIntOrNull()?.takeIf { it >= 1 }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        val r = api.get("/stories/$sid/chapters/$n")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = InkittParser.parseChapterPage(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Inkitt: chapter $n of $sid unavailable")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = n.toString(), index = n - 1, title = c.title.orEmpty()),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Inkitt sign-in isn't supported")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Inkitt sign-in isn't supported")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())

    // ─── helpers ──────────────────────────────────────────────────────────

    /** Accepts a bare numeric id or a story URL (`inkitt.com/stories/<id>[…]`). */
    internal fun storyId(raw: String): String? {
        val s = raw.trim()
        if (s.all(Char::isDigit) && s.isNotEmpty()) return s
        val m = Regex("""(?:^|/)stories/(?:[a-z-]+/)?(\d+)""").find(s) ?: return null
        if ("://" in s && !s.substringAfter("://").substringBefore('/').contains("inkitt.com")) return null
        return m.groupValues[1]
    }

    private fun localId(fictionId: String): String? =
        fictionId.removePrefix("$ID:").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    private fun empty(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page.coerceAtLeast(1), hasNext = false))

    companion object {
        const val ID = "inkitt"
    }
}
