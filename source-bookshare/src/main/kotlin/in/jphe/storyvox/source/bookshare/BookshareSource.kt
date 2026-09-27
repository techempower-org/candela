package `in`.jphe.storyvox.source.bookshare

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
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
 * **Title metadata (`fictionDetail`)** also works on the key alone (guest
 * scope): synopsis, authors, cover — with an empty chapter list.
 *
 * **Content (`chapter`)** stays gated regardless of the key:
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

    /**
     * Title metadata (synopsis, authors, cover) via `GET /v2/titles/{id}` —
     * guest scope, so it works with the partner key alone and a Browse tap
     * opens a real detail page instead of dead-ending on the gate (#1462).
     * The chapter list stays empty: reading needs a downloaded DAISY file,
     * which is the gated [chapter] path below.
     */
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val key = config.apiKey() ?: return gate()
        val bookshareId = bookshareIdOf(fictionId)
            ?: return FictionResult.NotFound("Not a Bookshare id: $fictionId")
        return api.title(key, config.accessToken(), bookshareId).map { it.toDetail() }
    }

    // ── Content download stays gated (Protected DAISY / PDTB — see #1002). ──

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
        // Bookshare pages by an opaque `start` cursor (the previous page's
        // `next`), not by page number. Page 1 needs none; page N>1 replays
        // the cursor page N-1 returned. An unknown cursor (process restart
        // mid-scroll) ends the list instead of re-serving page 1 forever.
        val query = BookshareCursors.queryKey(title, author, category)
        val start = if (page <= 1) null else cursors.startFor(query, page) ?: return endOfList(page)
        return api.searchTitles(
            apiKey = key,
            accessToken = config.accessToken(),
            title = title,
            author = author,
            category = category,
            start = start,
        ).map { result ->
            cursors.record(query, page, result.next)
            result.toListPage(page)
        }
    }

    private val cursors = BookshareCursors()

    private fun endOfList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))

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
        id = fictionIdOf(bookshareId),
        sourceId = SourceIds.BOOKSHARE,
        title = title,
        author = authorDisplay(),
        coverUrl = coverUrl(),
        description = synopsis?.takeIf { it.isNotBlank() },
        tags = categories.mapNotNull { it.name.takeIf(String::isNotBlank) },
        // Bookshare titles are complete, published books.
        status = FictionStatus.COMPLETED,
    )

/** Maps title metadata → [FictionDetail]. No chapters until downloads unlock. `internal` for tests. */
internal fun BookshareTitle.toDetail(): FictionDetail {
    val summary = toSummary()
    return FictionDetail(
        summary = summary,
        chapters = emptyList(),
        genres = summary.tags,
    )
}

/**
 * Fiction ids are `"bookshare:<bookshareId>"`. The `sourceId:` prefix is
 * load-bearing: `FictionSourceIdResolver` routes colon-less ids to Royal Road
 * (#981/#1564), so a bare numeric Bookshare id would silently open the wrong
 * source. `internal` for unit tests.
 */
internal fun fictionIdOf(bookshareId: Long): String = "${SourceIds.BOOKSHARE}:$bookshareId"

/** Inverse of [fictionIdOf]; tolerates a bare numeric id. Null when not a Bookshare id. */
internal fun bookshareIdOf(fictionId: String): Long? =
    fictionId.removePrefix("${SourceIds.BOOKSHARE}:").toLongOrNull()?.takeIf { it > 0 }

/**
 * Remembers Bookshare's opaque `next` cursors so page-numbered callers can
 * page through a cursor-paged API. Keyed by query + page; bounded so a long
 * session of distinct searches can't grow it without limit. `internal` for tests.
 */
internal class BookshareCursors(private val maxEntries: Int = 256) {
    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    /** Cursor to fetch [page] of [query], i.e. the `next` that page-1 returned. */
    @Synchronized
    fun startFor(query: String, page: Int): String? = map["$query#$page"]

    /** Record that [page] of [query] returned [next] (the cursor for page+1). */
    @Synchronized
    fun record(query: String, page: Int, next: String?) {
        val slot = "$query#${page + 1}"
        if (next.isNullOrBlank()) map.remove(slot) else map[slot] = next
    }

    companion object {
        fun queryKey(title: String?, author: String?, category: String?): String =
            listOf(title.orEmpty(), author.orEmpty(), category.orEmpty()).joinToString("|")
    }
}
