package `in`.jphe.storyvox.source.fimfiction

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
import `in`.jphe.storyvox.source.fimfiction.net.FimfictionApi
import `in`.jphe.storyvox.source.fimfiction.parser.FimHtmlParser
import `in`.jphe.storyvox.source.fimfiction.parser.FimJson
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FimFiction — My Little Pony fan fiction. Ids: `fimfiction:<storyId>`,
 * chapters `…::<chapterId>` (the site's own chapter ids). Detail and the
 * chapter list come from the anonymous legacy JSON API (`/api/story.php`);
 * chapter bodies from the `/chapters/download/<id>/html` export; browse and
 * search from the `/stories` listing (tag search uses the site's own
 * `#tag` query syntax).
 */
@SourcePlugin(
    id = "fimfiction",
    displayName = "FimFiction",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "MLP fan fiction · anonymous, non-mature in browse",
    sourceUrl = "https://www.fimfiction.net",
    chipLabel = "FimFic",
    searchHint = "Search FimFiction stories",
    iconName = "AutoStories",
)
@Singleton
internal class FimfictionSource @Inject constructor(
    private val api: FimfictionApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "FimFiction"

    override suspend fun popular(page: Int) = list("order=rating", page)

    override suspend fun latestUpdates(page: Int) = list("order=updated", page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val g = genre.trim()
        if (g.isEmpty()) return FictionResult.NotFound("FimFiction: empty genre")
        return list("q=" + URLEncoder.encode("#$g", "UTF-8") + "&order=rating", page)
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        if (term.isEmpty()) {
            val g = query.genres.firstOrNull()
                ?: return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
            return byGenre(g, page)
        }
        return list("q=" + URLEncoder.encode(term, "UTF-8"), page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val sid = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val r = api.get("/api/story.php?story=$sid", FimfictionApi.JSON)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val envelope = FimJson.parseStory(r.value)
            ?: return FictionResult.NetworkError("FimFiction: unexpected API response")
        val s = envelope.story
            ?: return FictionResult.NotFound("FimFiction: ${envelope.error ?: "story $sid not found"}")
        val fid = "$ID:${s.id}"
        val genres = s.categories.orEmpty().filterValues { it }.keys.toList()
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = s.title,
                    author = s.author?.name?.ifBlank { null } ?: "Unknown",
                    coverUrl = s.fullImage ?: s.image,
                    description = s.shortDescription ?: s.description,
                    tags = listOfNotNull(s.contentRatingText) + genres,
                    status = status(s.status),
                    chapterCount = s.chapters.size.takeIf { it > 0 } ?: s.chapterCount,
                    companionSourceUrl = s.url ?: api.absolute("/story/${s.id}"),
                ),
                chapters = s.chapters.mapIndexed { i, c ->
                    ChapterInfo(
                        id = "$fid::${c.id}",
                        sourceChapterId = c.id.toString(),
                        index = i,
                        title = c.title.ifBlank { "Chapter ${i + 1}" },
                        publishedAt = c.dateModified?.times(1000),
                        wordCount = c.words,
                    )
                },
                genres = genres,
                wordCount = s.words,
                views = s.totalViews ?: s.views,
                lastUpdatedAt = s.dateModified?.times(1000),
                authorId = s.author?.id?.toString(),
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        if (localId(fictionId) == null) return FictionResult.NotFound("bad id $fictionId")
        val ch = chapterId.substringAfterLast("::").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        val r = api.get("/chapters/download/$ch/html")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = FimHtmlParser.parseChapterDownload(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("FimFiction: chapter $ch unavailable")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = ch, index = 0, title = c.title.orEmpty()),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("FimFiction sign-in isn't supported yet")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("FimFiction sign-in isn't supported yet")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(GENRES)

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun list(query: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val p = page.coerceAtLeast(1)
        val r = api.get("/stories?$query&page=$p")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = FimHtmlParser.parseList(r.value, api.baseUrl)
        return FictionResult.Success(
            ListPage(
                items = parsed.cards.map { c ->
                    FictionSummary(
                        id = "$ID:${c.storyId}",
                        sourceId = ID,
                        title = c.title,
                        author = c.author.ifBlank { "Unknown" },
                        coverUrl = c.cover,
                        description = c.description,
                        tags = c.tags,
                        companionSourceUrl = api.absolute("/story/${c.storyId}"),
                    )
                },
                page = p,
                hasNext = parsed.hasNext && parsed.cards.isNotEmpty(),
            ),
        )
    }

    private fun status(raw: String?): FictionStatus = when (raw?.trim()?.lowercase()) {
        "complete", "completed" -> FictionStatus.COMPLETED
        "hiatus", "on hiatus" -> FictionStatus.HIATUS
        "cancelled" -> FictionStatus.DROPPED
        else -> FictionStatus.ONGOING
    }

    private fun localId(fictionId: String): String? =
        fictionId.removePrefix("$ID:").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    companion object {
        const val ID = "fimfiction"

        /** FimFiction's genre tags, usable directly in `#tag` query syntax. */
        val GENRES = listOf(
            "Adventure", "Comedy", "Drama", "Horror", "Mystery", "Romance", "Sad",
            "Thriller", "Dark", "Slice of Life", "Alternate Universe", "Crossover", "Tragedy",
        )
    }
}
