package `in`.jphe.storyvox.source.quotev

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.quotev.net.QuotevApi
import `in`.jphe.storyvox.source.quotev.parser.QuotevParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Quotev — fan fiction and original stories. Ids: `quotev:<storyId>.<slug>`,
 * chapters `…::<n>` (position in the story's chapter strip).
 *
 * Quotev fronts EVERY page with its own JS wall (observed 2026-10, any user
 * agent), so until a WebView clearance path is wired all requests surface as
 * `FictionResult.Cloudflare` — the FanFiction.net posture. Search takes a
 * pasted story URL or id; there is no known anonymous search endpoint.
 */
@SourcePlugin(
    id = "quotev",
    displayName = "Quotev",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Fan fiction & quizzes · behind a browser check",
    sourceUrl = "https://www.quotev.com",
    searchHint = "Paste a Quotev story URL or id",
    iconName = "AutoStories",
)
@Singleton
internal class QuotevSource @Inject constructor(
    private val api: QuotevApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Quotev"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) return empty(page)
        val r = api.get("/stories")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val cards = QuotevParser.parseList(r.value, api.baseUrl)
        return FictionResult.Success(
            ListPage(
                items = cards.map { c ->
                    FictionSummary(
                        id = fictionId(c.ref),
                        sourceId = ID,
                        title = c.title,
                        author = "Unknown",
                        companionSourceUrl = api.absolute("/story/${c.ref.storyId}/${c.ref.slug}"),
                    )
                },
                page = 1,
                hasNext = false,
            ),
        )
    }

    override suspend fun latestUpdates(page: Int) = empty(page)
    override suspend fun byGenre(genre: String, page: Int) = empty(page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val ref = parseInput(query.term) ?: return empty(query.page)
        if (query.page > 1) return empty(query.page)
        return when (val d = detail(ref)) {
            is FictionResult.Failure -> if (d is FictionResult.NotFound) empty(1) else d
            is FictionResult.Success -> FictionResult.Success(
                ListPage(listOf(d.value.summary), 1, hasNext = false),
            )
        }
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        return detail(ref)
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val n = chapterId.substringAfterLast("::").toIntOrNull()?.takeIf { it >= 1 }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        val r = api.get("/story/${ref.storyId}/${ref.slug}/$n")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = QuotevParser.parseChapter(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Quotev: chapter $n of ${ref.storyId} unavailable")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = n.toString(), index = n - 1, title = c.title.orEmpty()),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Quotev sign-in isn't supported")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Quotev sign-in isn't supported")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun detail(ref: QuotevParser.Ref): FictionResult<FictionDetail> {
        val r = api.get("/story/${ref.storyId}/${ref.slug}")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val s = QuotevParser.parseStory(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Quotev: story ${ref.storyId} not found")
        val fid = fictionId(ref)
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = s.title,
                    author = s.author.ifBlank { "Unknown" },
                    description = s.description,
                    chapterCount = s.chapterNumbers.size,
                    companionSourceUrl = api.absolute("/story/${ref.storyId}/${ref.slug}"),
                ),
                chapters = s.chapterNumbers.mapIndexed { i, n ->
                    ChapterInfo(id = "$fid::$n", sourceChapterId = n.toString(), index = i, title = "Chapter $n")
                },
            ),
        )
    }

    /** Accepts a bare story id or a story URL on Quotev's own host. */
    internal fun parseInput(raw: String): QuotevParser.Ref? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.all(Char::isDigit)) return QuotevParser.Ref(s, "x")
        if ("://" in s && !s.substringAfter("://").substringBefore('/').contains("quotev.com")) return null
        return QuotevParser.storyRef(s)
    }

    private fun fictionId(ref: QuotevParser.Ref) = "$ID:${ref.storyId}.${ref.slug}"

    private fun parseId(fictionId: String): QuotevParser.Ref? {
        val local = fictionId.removePrefix("$ID:")
        if (local.isEmpty() || local == fictionId) return null
        val sid = local.substringBefore('.')
        val slug = local.substringAfter('.', "").ifEmpty { "x" }
        return if (sid.isNotEmpty() && sid.all(Char::isDigit)) QuotevParser.Ref(sid, slug) else null
    }

    private fun empty(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page.coerceAtLeast(1), hasNext = false))

    companion object {
        const val ID = "quotev"
    }
}
