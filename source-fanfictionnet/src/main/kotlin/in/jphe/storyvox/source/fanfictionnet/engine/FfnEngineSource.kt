package `in`.jphe.storyvox.source.fanfictionnet.engine

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterBody
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import java.net.URLEncoder

/**
 * Static description of one site on the FanFiction.net engine.
 *
 * @property defaultCategory path browsed by popular()/latestUpdates(), e.g.
 *  `book/Harry-Potter`. FFN has no site-wide "popular" list, so we use the
 *  biggest archive sorted by favourites.
 * @property categories display label → category path, surfaced via genres().
 *  byGenre() also accepts a raw path containing `/` (e.g. `anime/One-Piece`)
 *  so a user can reach any archive the curated list doesn't cover.
 */
data class FfnSite(
    val id: String,
    val displayName: String,
    val defaultCategory: String,
    val categories: Map<String, String>,
)

/**
 * Shared [FictionSource] for FanFiction.net-engine sites. Subclasses only
 * provide the [FfnSite] and an [FfnEngineApi]; KSP binds the subclass.
 *
 * Ratings: list pages are requested with FFN's default rating window
 * (K through T) — Candela is a family-facing app, so M-rated works only
 * appear when opened directly by id/URL.
 */
abstract class FfnEngineSource(
    private val api: FfnEngineApi,
    private val site: FfnSite,
) : FictionSource {

    override val id: String get() = site.id
    override val displayName: String get() = site.displayName

    override suspend fun popular(page: Int) = category(site.defaultCategory, SORT_FAVS, page)

    override suspend fun latestUpdates(page: Int) = category(site.defaultCategory, SORT_UPDATED, page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val path = site.categories[genre] ?: genre.takeIf { '/' in it }
            ?: return FictionResult.NotFound("${site.displayName}: unknown category '$genre'")
        return category(path, SORT_FAVS, page)
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        if (term.isEmpty()) {
            val genre = query.genres.firstOrNull()
                ?: return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
            return byGenre(genre, page)
        }
        val q = URLEncoder.encode(term, "UTF-8")
        return listPage("/search/?keywords=$q&ready=1&type=story&ppage=$page", page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val storyId = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        return when (val r = api.getHtml("/s/$storyId/1/")) {
            is FictionResult.Failure -> r
            is FictionResult.Success -> {
                val h = FfnEngineParser.parseStory(r.value, api.baseUrl, storyId)
                    ?: return FictionResult.NotFound("${site.displayName}: story $storyId not found")
                val fid = fictionIdOf(storyId)
                // Chapter 1's body is already in hand — hand it over so the
                // first play doesn't refetch (one fewer CF-exposed request).
                val first = FfnEngineParser.parseChapter(r.value, api.baseUrl, 1)
                FictionResult.Success(
                    FictionDetail(
                        summary = FictionSummary(
                            id = fid,
                            sourceId = site.id,
                            title = h.title,
                            author = h.author.ifBlank { "Unknown" },
                            coverUrl = h.coverUrl,
                            description = h.summary,
                            tags = h.categories.drop(1) + h.genres,
                            status = FfnEngineParser.status(h.complete),
                            chapterCount = h.chapterTitles.size,
                            companionSourceUrl = api.absolute("/s/$storyId/1/"),
                        ),
                        chapters = FfnEngineParser.toChapterInfos(fid, h.chapterTitles),
                        genres = h.genres,
                        wordCount = h.words,
                        lastUpdatedAt = h.updatedAt,
                        authorId = h.authorId,
                        prefetchedBodies = first?.let {
                            mapOf("$fid::1" to ChapterBody(it.html, it.plain))
                        }.orEmpty(),
                    ),
                )
            }
        }
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val storyId = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val n = chapterId.substringAfterLast("::").toIntOrNull()
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        return when (val r = api.getHtml("/s/$storyId/$n/")) {
            is FictionResult.Failure -> r
            is FictionResult.Success -> {
                val c = FfnEngineParser.parseChapter(r.value, api.baseUrl, n)
                    ?: return FictionResult.NotFound("${site.displayName}: chapter $n of $storyId not found")
                val fid = fictionIdOf(storyId)
                FictionResult.Success(
                    ChapterContent(
                        info = ChapterInfo(
                            id = "$fid::$n", sourceChapterId = n.toString(), index = n - 1, title = c.title,
                        ),
                        htmlBody = c.html,
                        plainBody = c.plain,
                    ),
                )
            }
        }
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("${site.displayName} follows aren't supported yet")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("${site.displayName} follows aren't supported yet")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(site.categories.keys.toList())

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun category(path: String, sort: Int, page: Int): FictionResult<ListPage<FictionSummary>> {
        val p = page.coerceAtLeast(1)
        return listPage("/${path.trim('/')}/?&srt=$sort&p=$p", p)
    }

    private suspend fun listPage(path: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        when (val r = api.getHtml(path)) {
            is FictionResult.Failure -> r
            is FictionResult.Success -> {
                val parsed = FfnEngineParser.parseList(r.value, api.baseUrl)
                FictionResult.Success(
                    ListPage(
                        items = parsed.items.map { it.toSummary() },
                        page = page,
                        hasNext = parsed.hasNext && parsed.items.isNotEmpty(),
                    ),
                )
            }
        }

    private fun FfnEngineParser.ListItem.toSummary() = FictionSummary(
        id = fictionIdOf(storyId),
        sourceId = site.id,
        title = title,
        author = author.ifBlank { "Unknown" },
        coverUrl = coverUrl,
        description = summary,
        status = FfnEngineParser.status(complete),
        chapterCount = chapters,
    )

    private fun fictionIdOf(storyId: String) = "${site.id}:$storyId"

    private fun localId(fictionId: String): String? =
        fictionId.removePrefix("${site.id}:").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    companion object {
        const val SORT_UPDATED = 1
        const val SORT_FAVS = 4
    }
}
