package `in`.jphe.storyvox.source.epicfreegames

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.epicfreegames.net.EpicFreeGamesApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Epic Free Games source (scaffolded by scripts/new-source.sh).
 *
 * Every member is stubbed with an honest [FictionResult.NotFound] — wire each
 * one to [EpicFreeGamesApi] and map the response into the core-data models. The
 * contract test in src/test fails until popular()/search()/etc. talk to the
 * Api; making it green is your definition of done. See
 * docs/CONTRIBUTING-SOURCES.md.
 *
 * The @SourcePlugin id below is the SINGLE source of truth for this backend's
 * identity — do NOT add a SourceIds constant (that table is frozen).
 */
@SourcePlugin(
    id = "epic-free-games",
    displayName = "Epic Free Games",
    defaultEnabled = false,
    category = SourceCategory.Ebook,
    supportsSearch = true,
    description = "One-line subtitle — surface + auth posture (fill me in)",
    sourceUrl = "https://example.com",
)
@Singleton
internal class EpicFreeGamesSource @Inject constructor(
    @Suppress("unused") private val api: EpicFreeGamesApi,
) : FictionSource {

    override val id: String = "epic-free-games"
    override val displayName: String = "Epic Free Games"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.NotFound("not implemented")

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> =
        FictionResult.NotFound("not implemented")

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> =
        FictionResult.NotFound("not implemented")

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("not implemented")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("not implemented")

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())
}
