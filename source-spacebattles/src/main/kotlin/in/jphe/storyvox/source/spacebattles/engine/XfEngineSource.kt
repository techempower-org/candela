package `in`.jphe.storyvox.source.spacebattles.engine

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery

/**
 * Shared [FictionSource] for XenForo boards with threadmarks (SpaceBattles,
 * Sufficient Velocity, Questionable Questing). Subclasses only provide the
 * [XfSite] and an [XfEngineApi]; KSP binds the subclass.
 *
 * A "fiction" is a thread (`<siteId>:<threadId>`); chapters are its
 * threadmarked posts (`…::<postId>`), read from the `/threadmarks` index.
 * Threads without threadmarks surface as NotFound — on these boards that is
 * the authors' own convention for "this is a story you can read in order".
 *
 * XenForo's full-text search needs a logged-in session, so search() instead
 * accepts what people actually pass around on these boards: a thread URL
 * (or bare thread id) — the LiveJournal input precedent.
 */
abstract class XfEngineSource(
    private val api: XfEngineApi,
    private val site: XfSite,
) : FictionSource {

    override val id: String get() = site.id
    override val displayName: String get() = site.displayName

    override suspend fun popular(page: Int) =
        forum(site.defaultForum, page, "?order=reply_count&direction=desc")

    override suspend fun latestUpdates(page: Int) = forum(site.defaultForum, page, "")

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val node = site.forums[genre] ?: genre.takeIf { it.matches(NODE_PATH) }
            ?: return FictionResult.NotFound("${site.displayName}: unknown forum '$genre'")
        return forum(node, page, "")
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val tid = parseThreadInput(query.term) ?: return empty(query.page)
        if (query.page > 1) return empty(query.page)
        return when (val r = threadmarksPage(tid, 1)) {
            is FictionResult.Failure -> if (r is FictionResult.NotFound) empty(1) else r
            is FictionResult.Success -> {
                val page = r.value
                if (page.marks.isEmpty()) return empty(1)
                FictionResult.Success(ListPage(listOf(summary(tid, page)), 1, hasNext = false))
            }
        }
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val tid = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val marks = mutableListOf<XfEngineParser.Threadmark>()
        var first: XfEngineParser.ThreadmarksPage? = null
        var pageNo = 1
        while (pageNo <= MAX_TM_PAGES) {
            val r = threadmarksPage(tid, pageNo)
            if (r !is FictionResult.Success) {
                // A failed first page fails the call; a failed later page keeps
                // the chapters already in hand.
                if (first == null) return r as FictionResult.Failure
                break
            }
            if (first == null) first = r.value
            marks += r.value.marks
            if (!r.value.hasNext || r.value.marks.isEmpty()) break
            pageNo++
        }
        val head = first ?: return FictionResult.NotFound("${site.displayName}: thread $tid not found")
        if (marks.isEmpty()) {
            return FictionResult.NotFound(
                "${site.displayName}: thread $tid has no threadmarks — only threadmarked stories can be read",
            )
        }
        val fid = fictionIdOf(tid)
        val deduped = marks.distinctBy { it.postId }
        return FictionResult.Success(
            FictionDetail(
                summary = summary(tid, head).copy(chapterCount = deduped.size),
                chapters = deduped.mapIndexed { i, m ->
                    ChapterInfo(
                        id = "$fid::${m.postId}",
                        sourceChapterId = m.postId,
                        index = i,
                        title = m.title,
                        publishedAt = m.createdAt,
                        wordCount = m.words?.toInt(),
                    )
                },
                wordCount = head.totalWords ?: deduped.mapNotNull { it.words }.takeIf { it.isNotEmpty() }?.sum(),
                lastUpdatedAt = deduped.mapNotNull { it.createdAt }.maxOrNull(),
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val tid = localId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val postId = chapterId.substringAfterLast("::").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?: return FictionResult.NotFound("bad chapter id $chapterId")
        // The post URL redirects to the right thread page; OkHttp follows it
        // and the post is located by its `post-<id>` anchor in that page.
        return when (val r = api.getHtml("/threads/$tid/post-$postId")) {
            is FictionResult.Failure -> r
            is FictionResult.Success -> {
                val p = XfEngineParser.parsePost(r.value, api.baseUrl, postId)
                    ?: return FictionResult.NotFound("${site.displayName}: post $postId not found in thread $tid")
                FictionResult.Success(
                    ChapterContent(
                        info = ChapterInfo(id = chapterId, sourceChapterId = postId, index = 0, title = p.title.orEmpty()),
                        htmlBody = p.html,
                        plainBody = p.plain,
                    ),
                )
            }
        }
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("${site.displayName} sign-in isn't supported yet")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("${site.displayName} sign-in isn't supported yet")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(site.forums.keys.toList())

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun forum(node: String, page: Int, query: String): FictionResult<ListPage<FictionSummary>> {
        val p = page.coerceAtLeast(1)
        val path = if (p == 1) "/forums/$node/$query" else "/forums/$node/page-$p$query"
        return when (val r = api.getHtml(path)) {
            is FictionResult.Failure -> r
            is FictionResult.Success -> {
                val parsed = XfEngineParser.parseForum(r.value, api.baseUrl)
                FictionResult.Success(
                    ListPage(
                        items = parsed.threads.map { t ->
                            FictionSummary(
                                id = fictionIdOf(t.threadId),
                                sourceId = site.id,
                                title = t.title,
                                author = t.author.ifBlank { "Unknown" },
                                chapterCount = null,
                                status = FictionStatus.ONGOING,
                                companionSourceUrl = api.absolute("/threads/${t.threadId}/"),
                            )
                        },
                        page = p,
                        hasNext = parsed.hasNext && parsed.threads.isNotEmpty(),
                    ),
                )
            }
        }
    }

    private suspend fun threadmarksPage(tid: String, page: Int): FictionResult<XfEngineParser.ThreadmarksPage> =
        when (val r = api.getHtml("/threads/$tid/threadmarks?per_page=$TM_PER_PAGE&page=$page")) {
            is FictionResult.Failure -> r
            is FictionResult.Success -> FictionResult.Success(XfEngineParser.parseThreadmarks(r.value, api.baseUrl))
        }

    private fun summary(tid: String, page: XfEngineParser.ThreadmarksPage) = FictionSummary(
        id = fictionIdOf(tid),
        sourceId = site.id,
        title = page.title ?: "Thread $tid",
        author = page.marks.firstOrNull()?.author?.ifBlank { null } ?: "Unknown",
        chapterCount = page.marks.size,
        status = FictionStatus.ONGOING,
        companionSourceUrl = api.absolute("/threads/$tid/"),
    )

    private fun empty(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page.coerceAtLeast(1), hasNext = false))

    /** Accepts a bare thread id, a `/threads/…` path, or a full URL on this site's host. */
    internal fun parseThreadInput(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.all(Char::isDigit)) return s
        if ("://" in s) {
            val host = s.substringAfter("://").substringBefore('/').lowercase()
            val own = api.baseUrl.substringAfter("://").substringBefore('/').lowercase()
            if (host != own) return null
        }
        return XfEngineParser.threadIdOf(s)
    }

    private fun fictionIdOf(threadId: String) = "${site.id}:$threadId"

    private fun localId(fictionId: String): String? =
        fictionId.removePrefix("${site.id}:").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }

    companion object {
        /** `name.123` raw forum node path accepted by byGenre(). */
        private val NODE_PATH = Regex("""[a-z0-9-]+\.\d+""")

        /** svThreadmarks honors per_page; 100 keeps even long fics to a few pages. */
        const val TM_PER_PAGE = 100

        /** 20 × 100 threadmarks is beyond any real story; a hard stop, not a limit users hit. */
        const val MAX_TM_PAGES = 20
    }
}
