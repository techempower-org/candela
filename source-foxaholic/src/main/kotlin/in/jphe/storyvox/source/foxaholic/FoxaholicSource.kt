package `in`.jphe.storyvox.source.foxaholic

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
import `in`.jphe.storyvox.source.foxaholic.net.FoxaholicApi
import `in`.jphe.storyvox.source.foxaholic.parser.MadaraParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Foxaholic — fan-translated Asian web novels on the WordPress "Madara"
 * theme. Ids: `foxaholic:<novelSlug>`; chapters `…::<chapterPath>` with `/`
 * in the path encoded as `~` (paths like `vol-1/chapter-3` exist).
 *
 * Locked/premium chapters are skipped. Fronted by Cloudflare; challenged
 * requests surface as `FictionResult.Cloudflare`.
 */
@SourcePlugin(
    id = "foxaholic",
    displayName = "Foxaholic",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Translated web novels · anonymous",
    sourceUrl = "https://www.foxaholic.com",
    searchHint = "Search Foxaholic novels",
    iconName = "AutoStories",
)
@Singleton
internal class FoxaholicSource @Inject constructor(
    private val api: FoxaholicApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Foxaholic"

    override suspend fun popular(page: Int) = list(archive("/novel/", page, "views"), page)

    override suspend fun latestUpdates(page: Int) = list(archive("/novel/", page, "latest"), page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val slug = genre.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isEmpty()) return FictionResult.NotFound("Foxaholic: empty genre")
        return list(archive("/genre/$slug/", page, null), page)
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        if (term.isEmpty()) {
            val g = query.genres.firstOrNull() ?: return FictionResult.Success(ListPage(emptyList(), page, false))
            return byGenre(g, page)
        }
        val q = URLEncoder.encode(term, "UTF-8")
        val prefix = if (page > 1) "/page/$page/" else "/"
        return list("$prefix?s=$q&post_type=wp-manga", page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val slug = slugOf(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val r = api.get("/novel/$slug/")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val n = MadaraParser.parseNovel(r.value, api.baseUrl) ?: return FictionResult.NotFound("Foxaholic: novel not found")
        val rows = api.postForm("/novel/$slug/ajax/chapters/", emptyMap())
        if (rows !is FictionResult.Success) return rows as FictionResult.Failure
        val chapters = MadaraParser.parseChapterRows(rows.value, slug)
        val fid = "$ID:$slug"
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = n.title,
                    author = n.author.ifBlank { "Unknown" },
                    coverUrl = n.cover,
                    description = n.description,
                    tags = n.genres,
                    status = if (n.completed) FictionStatus.COMPLETED else FictionStatus.ONGOING,
                    chapterCount = chapters.size,
                    companionSourceUrl = api.absolute("/novel/$slug/"),
                ),
                chapters = chapters.mapIndexed { i, c ->
                    val local = c.path.replace('/', '~')
                    ChapterInfo(id = "$fid::$local", sourceChapterId = local, index = i, title = c.title)
                },
                genres = n.genres,
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val slug = slugOf(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val local = chapterId.substringAfterLast("::")
        val path = local.replace('~', '/').trim('/')
        if (path.isEmpty() || ".." in path) return FictionResult.NotFound("bad chapter $chapterId")
        val r = api.get("/novel/$slug/$path/")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = MadaraParser.parseChapter(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Foxaholic: chapter locked or unavailable")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = local, index = 0, title = c.title.orEmpty()),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Foxaholic sign-in isn't supported")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Foxaholic sign-in isn't supported")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(GENRES)

    private fun archive(base: String, page: Int, orderBy: String?): String {
        val p = page.coerceAtLeast(1)
        val path = if (p > 1) "${base}page/$p/" else base
        return if (orderBy != null) "$path?m_orderby=$orderBy" else path
    }

    private suspend fun list(path: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val r = api.get(path)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = MadaraParser.parseList(r.value, api.baseUrl)
        return FictionResult.Success(
            ListPage(
                items = parsed.cards.map { c ->
                    FictionSummary(
                        id = "$ID:${c.slug}",
                        sourceId = ID,
                        title = c.title,
                        author = "Unknown",
                        coverUrl = c.cover,
                        description = c.description,
                        tags = c.genres,
                    )
                },
                page = page.coerceAtLeast(1),
                hasNext = parsed.hasNext && parsed.cards.isNotEmpty(),
            ),
        )
    }

    private fun slugOf(fictionId: String): String? =
        fictionId.removePrefix("$ID:").takeIf { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9._%-]+")) }

    companion object {
        const val ID = "foxaholic"
        val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Historical", "Josei", "Martial Arts",
            "Mystery", "Romance", "School Life", "Sci-fi", "Shoujo", "Slice of Life", "Supernatural",
            "Tragedy", "Wuxia", "Xianxia", "Xuanhuan",
        )
    }
}
