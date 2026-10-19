package `in`.jphe.storyvox.source.tapas

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
import `in`.jphe.storyvox.source.tapas.net.TapasApi
import `in`.jphe.storyvox.source.tapas.parser.TapasParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tapas community novels. Ids carry the numeric series id plus the URL slug
 * (`tapas:<seriesId>.<slug>`); chapters are episodes (`…::<episodeId>`),
 * listed oldest-first from the site's XHR episodes JSON. Locked episodes
 * (coin-gated early access) are skipped, like Foxaholic's premium chapters.
 *
 * The novel directory pages are client-rendered, so browse starts from
 * search (`/search?t=NOVELS`, server-rendered) or a pasted series URL;
 * popular()/latestUpdates() stay empty — the LiveJournal precedent.
 */
@SourcePlugin(
    id = "tapas",
    displayName = "Tapas",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Community novels · anonymous, free episodes only",
    sourceUrl = "https://tapas.io",
    searchHint = "Search Tapas novels, or paste a series URL",
    iconName = "AutoStories",
)
@Singleton
internal class TapasSource @Inject constructor(
    private val api: TapasApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Tapas"

    // The novels directory is client-rendered — nothing stable to browse.
    override suspend fun popular(page: Int) = empty(page)
    override suspend fun latestUpdates(page: Int) = empty(page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        search(SearchQuery(term = genre, page = page))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        if (term.isEmpty()) return empty(page)
        // A pasted series URL resolves directly.
        seriesSlug(term)?.let { slug ->
            if (page > 1) return empty(page)
            return when (val d = detailBySlug(slug, seriesId = null)) {
                is FictionResult.Failure -> if (d is FictionResult.NotFound) empty(1) else d
                is FictionResult.Success -> FictionResult.Success(
                    ListPage(listOf(d.value.summary), 1, hasNext = false),
                )
            }
        }
        val q = URLEncoder.encode(term, "UTF-8")
        val r = api.get("/search?t=NOVELS&q=$q&pageNumber=$page")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = TapasParser.parseSearch(r.value, api.baseUrl, page)
        return FictionResult.Success(
            ListPage(
                items = parsed.cards.map { c ->
                    FictionSummary(
                        id = "$ID:${c.seriesId}.${c.slug}",
                        sourceId = ID,
                        title = c.title,
                        author = "Unknown",
                        coverUrl = c.cover,
                        companionSourceUrl = api.absolute("/series/${c.slug}"),
                    )
                },
                page = page,
                hasNext = parsed.hasNext,
            ),
        )
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        return detailBySlug(ref.slug, ref.seriesId)
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        if (parseId(fictionId) == null) return FictionResult.NotFound("bad id $fictionId")
        val ep = chapterId.substringAfterLast("::").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        val r = api.get("/episode/$ep")
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val c = TapasParser.parseEpisodePage(r.value, api.baseUrl)
            ?: return FictionResult.NotFound("Tapas: episode $ep unavailable (locked or removed)")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = ep, index = 0, title = c.title.orEmpty()),
                htmlBody = c.html,
                plainBody = c.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Tapas sign-in isn't supported yet")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Tapas sign-in isn't supported yet")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())

    // ─── helpers ──────────────────────────────────────────────────────────

    private data class Ref(val seriesId: String?, val slug: String)

    private suspend fun detailBySlug(slug: String, seriesId: String?): FictionResult<FictionDetail> {
        val infoR = api.get("/series/$slug/info")
        if (infoR !is FictionResult.Success) return infoR as FictionResult.Failure
        val info = TapasParser.parseInfo(infoR.value, api.baseUrl)
        val sid = seriesId ?: info.seriesId
            ?: return FictionResult.NotFound("Tapas: series $slug not found")
        val title = info.title ?: return FictionResult.NotFound("Tapas: series $slug not found")

        val episodes = mutableListOf<TapasParser.Episode>()
        var page = 1
        while (page <= MAX_EPISODE_PAGES) {
            val r = api.get(
                "/series/$sid/episodes?page=$page&sort=OLDEST&max_limit=$EPISODES_PER_PAGE",
                accept = TapasApi.JSON,
                xhr = true,
            )
            if (r !is FictionResult.Success) {
                if (episodes.isEmpty()) return r as FictionResult.Failure
                break
            }
            val parsed = TapasParser.parseEpisodes(r.value)
                ?: return FictionResult.NetworkError("Tapas: unexpected episodes response")
            episodes += parsed.episodes
            if (!parsed.hasNext || parsed.episodes.isEmpty()) break
            page++
        }
        val readable = episodes.filter { it.readable }.distinctBy { it.id }
        val fid = "$ID:$sid.$slug"
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = title,
                    author = info.author ?: "Unknown",
                    description = info.description,
                    tags = info.genres,
                    chapterCount = readable.size,
                    companionSourceUrl = api.absolute("/series/$slug"),
                ),
                chapters = readable.mapIndexed { i, e ->
                    ChapterInfo(
                        id = "$fid::${e.id}",
                        sourceChapterId = e.id,
                        index = i,
                        title = e.title.ifBlank { "Episode ${e.scene ?: (i + 1)}" },
                        publishedAt = e.publishedAt,
                    )
                },
                genres = info.genres,
                lastUpdatedAt = readable.mapNotNull { it.publishedAt }.maxOrNull(),
            ),
        )
    }

    /** `https://tapas.io/series/<slug>[/info]` (or a bare `/series/<slug>` path) → slug. */
    internal fun seriesSlug(raw: String): String? {
        val m = Regex("""(?:^|/)series/([^/?#\s]+)""").find(raw.trim()) ?: return null
        if ("://" in raw && !raw.substringAfter("://").substringBefore('/').contains("tapas.io")) return null
        return m.groupValues[1]
    }

    private fun parseId(fictionId: String): Ref? {
        val local = fictionId.removePrefix("$ID:")
        if (local.isEmpty() || local == fictionId) return null
        val sid = local.substringBefore('.')
        val slug = local.substringAfter('.', "")
        return when {
            sid.isNotEmpty() && sid.all(Char::isDigit) -> Ref(sid, slug.ifEmpty { sid })
            else -> null
        }
    }

    private fun empty(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page.coerceAtLeast(1), hasNext = false))

    companion object {
        const val ID = "tapas"
        const val EPISODES_PER_PAGE = 20

        /** 50 × 20 episodes outruns any community novel; a hard stop, not a user-visible limit. */
        const val MAX_EPISODE_PAGES = 50
    }
}
