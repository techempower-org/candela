package `in`.jphe.storyvox.source.wattpad

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
import `in`.jphe.storyvox.source.wattpad.net.WattpadApi
import `in`.jphe.storyvox.source.wattpad.net.WattpadJson
import kotlinx.serialization.SerializationException
import org.jsoup.Jsoup
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wattpad via its public JSON API (no scraping): `/api/v3/stories` for the
 * hot/new lists and story detail, `/v4/search/stories` for search, and
 * `/apiv2/storytext` for a part's HTML.
 *
 * Mature-flagged stories are filtered out of every list (Candela is a
 * family-facing app); a mature story opened directly by id still loads.
 * Paid "Wattpad Originals" parts return an empty body — surfaced as
 * NotFound with an explanation rather than an empty chapter.
 *
 * Ids: `wattpad:<storyId>`, chapters `wattpad:<storyId>::<partId>`.
 */
@SourcePlugin(
    id = "wattpad",
    displayName = "Wattpad",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Web fiction · public API, mature stories hidden",
    sourceUrl = "https://www.wattpad.com",
    searchHint = "Search Wattpad stories",
    iconName = "AutoStories",
)
@Singleton
internal class WattpadSource @Inject constructor(
    private val api: WattpadApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Wattpad"

    override suspend fun popular(page: Int) = feed("hot", page)

    override suspend fun latestUpdates(page: Int) = feed("new", page)

    override suspend fun byGenre(genre: String, page: Int) = searchTerm(genre, page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().ifEmpty { query.genres.firstOrNull().orEmpty() }
        if (term.isEmpty()) return FictionResult.Success(ListPage(emptyList(), query.page, hasNext = false))
        return searchTerm(term, query.page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val sid = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val r = api.get("/api/v3/stories/$sid?fields=$DETAIL_FIELDS", WattpadApi.JSON)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val (story, parts) = parse { WattpadJson.parseStory(r.value) }
            ?: return FictionResult.NotFound("Wattpad: story $sid not found")
        val fid = "$ID:$sid"
        return FictionResult.Success(
            FictionDetail(
                summary = story.toSummary().copy(chapterCount = parts.size),
                chapters = parts.mapIndexed { i, p ->
                    ChapterInfo(
                        id = "$fid::${p.id}",
                        sourceChapterId = p.id,
                        index = i,
                        title = p.title,
                        publishedAt = p.createdAt,
                        wordCount = p.wordCount,
                    )
                },
                genres = story.tags.take(8),
                wordCount = parts.sumOf { (it.wordCount ?: 0).toLong() }.takeIf { it > 0 },
                lastUpdatedAt = parts.mapNotNull { it.createdAt }.maxOrNull(),
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val partId = chapterId.substringAfterLast("::").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        val r = api.get("/apiv2/storytext?id=$partId", WattpadApi.HTML)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val body = Jsoup.parseBodyFragment(r.value).body()
        body.select("script, style, img").remove()
        val plain = HtmlTextLite.plain(body)
        if (plain.isBlank()) {
            return FictionResult.NotFound("Wattpad: this part is paywalled or unavailable")
        }
        // Title comes from the detail's chapter list; the storytext endpoint
        // returns body only. The repository keeps the cached ChapterInfo title.
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = partId, index = 0, title = ""),
                htmlBody = body.html(),
                plainBody = plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Wattpad library sync isn't supported yet")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Wattpad library sync isn't supported yet")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(GENRES)

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun feed(filter: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val p = page.coerceAtLeast(1)
        val path = "/api/v3/stories?filter=$filter&mature=0&limit=$PAGE&offset=${(p - 1) * PAGE}" +
            "&fields=stories($LIST_FIELDS),nextUrl"
        return list(path, p)
    }

    private suspend fun searchTerm(term: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val p = page.coerceAtLeast(1)
        val q = URLEncoder.encode(term, "UTF-8")
        val path = "/v4/search/stories?query=$q&mature=false&limit=$PAGE&offset=${(p - 1) * PAGE}" +
            "&fields=stories($LIST_FIELDS),total,nextUrl"
        return list(path, p)
    }

    private suspend fun list(path: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val r = api.get(path, WattpadApi.JSON)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = parse { WattpadJson.parseList(r.value) }
            ?: return FictionResult.NetworkError("Wattpad returned an unexpected response shape")
        return FictionResult.Success(
            ListPage(parsed.stories.filterNot { it.mature }.map { it.toSummary() }, page, parsed.hasNext),
        )
    }

    private inline fun <T> parse(block: () -> T): T? = try {
        block()
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun WattpadJson.Story.toSummary() = FictionSummary(
        id = "$ID:$id",
        sourceId = ID,
        title = title,
        author = author.ifBlank { "Unknown" },
        coverUrl = cover,
        description = description,
        tags = tags.take(8),
        status = if (completed) FictionStatus.COMPLETED else FictionStatus.ONGOING,
        chapterCount = parts,
        companionSourceUrl = "https://www.wattpad.com/story/$id",
    )

    private fun localId(fictionId: String) =
        fictionId.removePrefix("$ID:").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    companion object {
        const val ID = "wattpad"
        const val PAGE = 20
        const val LIST_FIELDS = "id,title,user(name),cover,description,tags,numParts,completed,mature"
        const val DETAIL_FIELDS = "id,title,user(name),cover,description,tags,numParts,completed,mature," +
            "parts(id,title,wordCount,createDate,draft,deleted)"
        val GENRES = listOf(
            "Romance", "Fantasy", "Science Fiction", "Mystery", "Thriller", "Horror", "Paranormal",
            "Adventure", "Humor", "Fanfiction", "Teen Fiction", "Historical Fiction", "Short Story",
            "Poetry", "Werewolf", "Vampire", "LGBTQ+", "Action",
        )
    }
}
