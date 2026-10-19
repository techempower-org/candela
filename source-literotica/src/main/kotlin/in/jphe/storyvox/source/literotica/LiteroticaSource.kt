package `in`.jphe.storyvox.source.literotica

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterBody
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
import `in`.jphe.storyvox.source.literotica.net.LiteroticaApi
import `in`.jphe.storyvox.source.literotica.parser.LitJson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Literotica — an 18+-only archive. Like `:source-aff` and
 * `:source-questionablequesting`, this module is built and tested in every
 * CI run but only linked into `:app` with `-Pcandela.adultSources=true`, so
 * Play and default sideload builds never contain it (see app/build.gradle.kts).
 *
 * A "fiction" is one submission (`literotica:<slug>`); its site pages are the
 * chapters (`…::<n>`). Everything goes through the site's own `/api/3` JSON
 * (the SPA's backend — undocumented, parsed tolerantly in [LitJson]); a
 * pasted `/s/<slug>` story URL is accepted as search input, which keeps the
 * source usable even if the search endpoint's shape drifts.
 */
@SourcePlugin(
    id = "literotica",
    displayName = "Literotica",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Adult fiction (18+) · anonymous",
    sourceUrl = "https://www.literotica.com",
    chipLabel = "Lit",
    searchHint = "Search Literotica, or paste a story URL",
    iconName = "AutoStories",
)
@Singleton
internal class LiteroticaSource @Inject constructor(
    private val api: LiteroticaApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Literotica"

    override suspend fun popular(page: Int) =
        list(api.apiPath("stories/top", """{"page":${page.coerceAtLeast(1)}}"""), page)

    override suspend fun latestUpdates(page: Int) =
        list(api.apiPath("stories/new", """{"page":${page.coerceAtLeast(1)}}"""), page)

    // No stable anonymous category listing is known for the SPA API; genre
    // browsing goes through search terms instead.
    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        search(SearchQuery(term = genre, page = page))

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        val page = query.page.coerceAtLeast(1)
        if (term.isEmpty()) return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        // A pasted story URL resolves directly — the dependable path.
        urlSlug(term)?.let { slug ->
            if (page > 1) return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
            return when (val d = fictionDetail("$ID:$slug")) {
                is FictionResult.Failure -> if (d is FictionResult.NotFound) {
                    FictionResult.Success(ListPage(emptyList(), 1, hasNext = false))
                } else d
                is FictionResult.Success -> FictionResult.Success(
                    ListPage(listOf(d.value.summary), 1, hasNext = false),
                )
            }
        }
        val q = term.replace("\"", "")
        return list(api.apiPath("search/stories", """{"q":"$q","page":$page}"""), page)
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val slug = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val r = api.get(api.apiPath("stories/$slug", """{"contentPage":1}"""))
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val page = LitJson.parseStory(r.value)
            ?: return FictionResult.NetworkError("Literotica: unexpected API response")
        val s = page.story ?: return FictionResult.NotFound("Literotica: story $slug not found")
        val fid = "$ID:${s.slug.ifEmpty { slug }}"
        val pages = (s.pagesCount ?: 1).coerceAtLeast(1)
        return FictionResult.Success(
            FictionDetail(
                summary = FictionSummary(
                    id = fid,
                    sourceId = ID,
                    title = s.title,
                    author = s.author.ifBlank { "Unknown" },
                    description = s.description,
                    tags = listOfNotNull(s.category),
                    status = FictionStatus.COMPLETED,
                    chapterCount = pages,
                    rating = s.rating?.toFloat(),
                    companionSourceUrl = api.absolute("/s/$slug"),
                ),
                chapters = (1..pages).map { n ->
                    ChapterInfo(id = "$fid::$n", sourceChapterId = n.toString(), index = n - 1, title = "Page $n")
                },
                genres = listOfNotNull(s.category),
                // Page 1's text is already in hand — don't refetch it on first play.
                prefetchedBodies = if (page.plain.isNotBlank()) {
                    mapOf("$fid::1" to ChapterBody(page.html, page.plain))
                } else emptyMap(),
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val slug = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val n = chapterId.substringAfterLast("::").toIntOrNull()?.takeIf { it >= 1 }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        val r = api.get(api.apiPath("stories/$slug", """{"contentPage":$n}"""))
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val page = LitJson.parseStory(r.value)
        if (page == null || page.plain.isBlank()) {
            return FictionResult.NotFound("Literotica: page $n of $slug unavailable")
        }
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = n.toString(), index = n - 1, title = "Page $n"),
                htmlBody = page.html,
                plainBody = page.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("Literotica sign-in isn't supported")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("Literotica sign-in isn't supported")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun list(path: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val r = api.get(path)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val parsed = LitJson.parseList(r.value)
        return FictionResult.Success(
            ListPage(
                items = parsed.stories.map { s ->
                    FictionSummary(
                        id = "$ID:${s.slug}",
                        sourceId = ID,
                        title = s.title,
                        author = s.author.ifBlank { "Unknown" },
                        description = s.description,
                        tags = listOfNotNull(s.category),
                        status = FictionStatus.COMPLETED,
                        chapterCount = s.pagesCount,
                        rating = s.rating?.toFloat(),
                        companionSourceUrl = api.absolute("/s/${s.slug}"),
                    )
                },
                page = page.coerceAtLeast(1),
                hasNext = parsed.hasNext,
            ),
        )
    }

    /** `https://www.literotica.com/s/<slug>` (or a bare `/s/<slug>` path) → slug. */
    internal fun urlSlug(raw: String): String? {
        val m = Regex("""(?:^|/)s/([a-z0-9-]+)""", RegexOption.IGNORE_CASE).find(raw.trim()) ?: return null
        if ("://" in raw && !raw.substringAfter("://").substringBefore('/').contains("literotica.com")) return null
        return m.groupValues[1].lowercase()
    }

    private fun localId(fictionId: String): String? =
        fictionId.removePrefix("$ID:").lowercase().takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]*")) }

    companion object {
        const val ID = "literotica"
    }
}
