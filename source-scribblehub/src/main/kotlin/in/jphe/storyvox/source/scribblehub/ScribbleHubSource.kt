package `in`.jphe.storyvox.source.scribblehub

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
import `in`.jphe.storyvox.source.scribblehub.net.ScribbleHubApi
import `in`.jphe.storyvox.source.scribblehub.parser.ScribbleHubParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scribble Hub — web novels and translations. Ids carry the URL slug because
 * Scribble Hub's chapter URLs embed it: `scribblehub:<seriesId>.<slug>`,
 * chapters `…::<chapterId>`. The chapter list comes from the site's own
 * admin-ajax TOC endpoint (one request for every chapter).
 *
 * Fronted by Cloudflare; challenged requests surface as
 * `FictionResult.Cloudflare`.
 */
@SourcePlugin(
    id = "scribblehub",
    displayName = "Scribble Hub",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Web novels · anonymous",
    sourceUrl = "https://www.scribblehub.com",
    searchHint = "Search Scribble Hub series",
    iconName = "AutoStories",
)
@Singleton
internal class ScribbleHubSource @Inject constructor(
    private val api: ScribbleHubApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Scribble Hub"

    override suspend fun popular(page: Int) = list("/series-ranking/?sort=1&order=1&pg=${page.coerceAtLeast(1)}", page)

    override suspend fun latestUpdates(page: Int) = list("/latest-series/?pg=${page.coerceAtLeast(1)}", page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val slug = genre.lowercase().trim().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        if (slug.isEmpty()) return FictionResult.NotFound("Scribble Hub: empty genre")
        return list("/genre/$slug/?pg=${page.coerceAtLeast(1)}", page)
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
        return list("$prefix?s=$q&post_type=fictionposts", page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val r = api.get("/series/${ref.seriesId}/${ref.slug}/")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val s = ScribbleHubParser.parseSeries(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Scribble Hub: series not found")
        val toc = api.postForm(
            "/wp-admin/admin-ajax.php",
            mapOf("action" to "wi_getreleases_pagination", "pagenum" to "-1", "mypostid" to ref.seriesId),
        )
        if (toc !is FictionResult.Success) return toc as FictionResult.Failure
        val chapters = ScribbleHubParser.parseToc(toc.value, api.baseUrl)
        val fid = fictionId(ref)
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = s.title,
                    author = s.author.ifBlank { "Unknown" },
                    coverUrl = s.cover,
                    description = s.description,
                    tags = s.genres + s.tags.take(10),
                    status = if (s.completed) FictionStatus.COMPLETED else FictionStatus.ONGOING,
                    chapterCount = chapters.size,
                    companionSourceUrl = api.absolute("/series/${ref.seriesId}/${ref.slug}/"),
                ),
                chapters = chapters.mapIndexed { i, c ->
                    ChapterInfo(id = "$fid::${c.chapterId}", sourceChapterId = c.chapterId, index = i, title = c.title)
                },
                genres = s.genres,
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val ch = chapterId.substringAfterLast("::").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return FictionResult.NotFound("bad chapter $chapterId")
        val r = api.get("/read/${ref.seriesId}-${ref.slug}/chapter/$ch/")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = ScribbleHubParser.parseChapter(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Scribble Hub: chapter unavailable")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = ch, index = 0, title = c.title.orEmpty()),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Scribble Hub sign-in isn't supported yet")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Scribble Hub sign-in isn't supported yet")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(GENRES)

    private suspend fun list(path: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val r = api.get(path)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = ScribbleHubParser.parseList(r.value, api.baseUrl)
        return FictionResult.Success(
            ListPage(
                items = parsed.cards.map { c ->
                    FictionSummary(
                        id = fictionId(c.ref),
                        sourceId = ID,
                        title = c.title,
                        author = c.author.ifBlank { "Unknown" },
                        coverUrl = c.cover,
                        description = c.description,
                        tags = c.genres,
                        chapterCount = c.chapters,
                    )
                },
                page = page.coerceAtLeast(1),
                hasNext = parsed.hasNext && parsed.cards.isNotEmpty(),
            ),
        )
    }

    private fun fictionId(ref: ScribbleHubParser.Ref) = "$ID:${ref.seriesId}.${ref.slug}"

    private fun parseId(fictionId: String): ScribbleHubParser.Ref? {
        val local = fictionId.removePrefix("$ID:")
        val sid = local.substringBefore('.')
        val slug = local.substringAfter('.', "").ifEmpty { "x" }
        return if (sid.isNotEmpty() && sid.all(Char::isDigit)) ScribbleHubParser.Ref(sid, slug) else null
    }

    companion object {
        const val ID = "scribblehub"
        val GENRES = listOf(
            "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Harem", "Historical", "Horror",
            "Isekai", "Josei", "Martial Arts", "Mecha", "Mystery", "Psychological",
            "Romance", "School Life", "Sci-fi", "Seinen", "Slice of Life", "Sports", "Supernatural",
            "Tragedy",
        )
    }
}
