package `in`.jphe.storyvox.source.bookshare

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.bookshare.net.BookshareApi
import `in`.jphe.storyvox.source.bookshare.net.BookshareTitle
import `in`.jphe.storyvox.source.bookshare.net.BookshareTitlesPage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1002 — Bookshare / accessible-library source.
 *
 * Bookshare (a Benetech program) is the largest accessible-book library for
 * people with print disabilities — 1M+ titles in DAISY format, free to
 * qualified users. The most mission-aligned source Candela could add.
 *
 * ## What works now vs. what stays gated
 *
 * **Discovery (search / browse / categories)** is wired to the Bookshare API
 * v2 ([BookshareApi]). It activates as soon as a partner `api_key` is supplied
 * through [BookshareConfig]; until then (the default in-memory config returns
 * none) these calls return [FictionResult.AuthRequired] — the interface's
 * graceful "no session" path.
 *
 * **Content (`fictionDetail` / `chapter`)** stays gated regardless of the key:
 * Bookshare copyrighted downloads are Protected DAISY (PDTB) — encrypted
 * per-user, fingerprinted, watermarked — and decryptable only under the
 * partnership (see the #1002 research comment). When that lands, `chapter`
 * will route an unprotected DAISY download through
 * [`DaisyParser`][in.jphe.storyvox.source.bookshare.parse.DaisyParser] /
 * [`Daisy202Parser`][in.jphe.storyvox.source.bookshare.parse.Daisy202Parser].
 *
 * Bookshare verifies a user's print-disability eligibility at account signup,
 * so Candela never collects proof-of-disability itself — it only forwards the
 * user's `api_key`/OAuth token from [BookshareConfig].
 */
@Singleton
@SourcePlugin(
    id = "bookshare",
    displayName = "Bookshare",
    defaultEnabled = false,
    category = SourceCategory.Ebook,
    supportsSearch = true,
    description = "Accessible DAISY library · partner API (see #1002)",
    sourceUrl = "https://www.bookshare.org",
    generateRouting = true,
)
internal class BookshareSource @Inject constructor(
    private val api: BookshareApi,
    private val config: BookshareConfig,
) : FictionSource {

    override val id: String = SourceIds.BOOKSHARE
    override val displayName: String = "Bookshare"

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        browse(page = page)

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Bookshare's catalog API exposes no "newest" sort; fall back to browse.
        browse(page = page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        browse(category = genre, page = page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        browse(
            title = query.term.takeIf { it.isNotBlank() },
            category = query.genres.firstOrNull(),
            page = query.page,
        )

    override suspend fun genres(): FictionResult<List<String>> {
        val key = config.apiKey() ?: return gate()
        return api.categories(key, config.accessToken())
            .map { page -> page.categories.mapNotNull { it.name.takeIf(String::isNotBlank) } }
    }

    // ── Content download stays gated (Protected DAISY / PDTB — see #1002). ──
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> = gate()

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> = gate()

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> = gate()

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> = gate()

    /** Shared discovery path: requires a configured `api_key`, else [gate]. */
    private suspend fun browse(
        title: String? = null,
        author: String? = null,
        category: String? = null,
        page: Int = 1,
    ): FictionResult<ListPage<FictionSummary>> {
        val key = config.apiKey() ?: return gate()
        return api.searchTitles(
            apiKey = key,
            accessToken = config.accessToken(),
            title = title,
            author = author,
            category = category,
        ).map { it.toListPage(page) }
    }

    private fun gate(): FictionResult.AuthRequired = FictionResult.AuthRequired(GATE_MESSAGE)

    companion object {
        private const val GATE_MESSAGE =
            "Bookshare needs a partner API key (and, for downloads, your verified " +
                "Bookshare sign-in + Protected-DAISY support) — see #1002."
    }
}

/** Maps a Bookshare titles page → the source layer's [ListPage]. `internal` for unit tests. */
internal fun BookshareTitlesPage.toListPage(page: Int): ListPage<FictionSummary> =
    ListPage(items = titles.map { it.toSummary() }, page = page, hasNext = next != null)

/** Maps one Bookshare title → [FictionSummary]. `internal` for unit tests. */
internal fun BookshareTitle.toSummary(): FictionSummary =
    FictionSummary(
        id = bookshareId.toString(),
        sourceId = SourceIds.BOOKSHARE,
        title = title,
        author = authorDisplay(),
        tags = categories.mapNotNull { it.name.takeIf(String::isNotBlank) },
    )
