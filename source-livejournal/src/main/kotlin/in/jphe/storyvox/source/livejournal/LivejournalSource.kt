package `in`.jphe.storyvox.source.livejournal

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterBody
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.livejournal.net.LivejournalApi
import `in`.jphe.storyvox.source.livejournal.parser.LjRssParser
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LiveJournal journals and communities, read through their public RSS.
 * A "fiction" is a journal — or a journal + tag, which is how LJ writers
 * usually group a serial (`livejournal:<journal>` / `livejournal:<journal>~<tag>`).
 * Posts are chapters (`…::<itemId>`), oldest first.
 *
 * Limits, both from LiveJournal itself: the feed only carries the 25 newest
 * posts (older ones aren't reachable without the JS-rendered site), and
 * there is no fiction directory — so this source is search-only: type a
 * journal name, `journal/tag`, or paste a journal URL.
 */
@SourcePlugin(
    id = "livejournal",
    displayName = "LiveJournal",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsSearch = true,
    description = "Journals & communities · public RSS, newest 25 posts",
    sourceUrl = "https://www.livejournal.com",
    chipLabel = "LJ",
    searchHint = "Journal name, journal/tag, or URL",
    iconName = "RssFeed",
)
@Singleton
internal class LivejournalSource @Inject constructor(
    private val api: LivejournalApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "LiveJournal"

    // No directory to browse — everything starts from search.
    override suspend fun popular(page: Int) = empty(page)
    override suspend fun latestUpdates(page: Int) = empty(page)
    override suspend fun byGenre(genre: String, page: Int) = empty(page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val ref = parseInput(query.term) ?: return empty(query.page)
        if (query.page > 1) return empty(query.page)
        return when (val f = feed(ref)) {
            is FictionResult.Failure -> if (f is FictionResult.NotFound) empty(1) else f
            is FictionResult.Success -> FictionResult.Success(ListPage(listOf(summary(ref, f.value)), 1, hasNext = false))
        }
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val f = feed(ref)
        if (f !is FictionResult.Success) return f as FictionResult.Failure
        val fid = ref.fictionId()
        return FictionResult.Success(
            FictionDetail(
                summary = summary(ref, f.value),
                chapters = f.value.posts.mapIndexed { i, p ->
                    ChapterInfo(id = "$fid::${p.itemId}", sourceChapterId = p.itemId, index = i, title = p.title, publishedAt = p.publishedAt)
                },
                lastUpdatedAt = f.value.posts.mapNotNull { it.publishedAt }.maxOrNull(),
                // The feed already carries every body — hand them all over.
                prefetchedBodies = f.value.posts.associate { "$fid::${it.itemId}" to ChapterBody(it.html, it.plain) },
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val ref = parseId(fictionId) ?: return FictionResult.NotFound("bad id $fictionId")
        val item = chapterId.substringAfterLast("::")
        val f = feed(ref)
        if (f !is FictionResult.Success) return f as FictionResult.Failure
        val idx = f.value.posts.indexOfFirst { it.itemId == item }
        if (idx < 0) return FictionResult.NotFound("LiveJournal: post is older than the journal's 25-post feed")
        val p = f.value.posts[idx]
        if (p.plain.isBlank()) return FictionResult.NotFound("LiveJournal: post is empty or friends-locked")
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(id = chapterId, sourceChapterId = item, index = idx, title = p.title, publishedAt = p.publishedAt),
                htmlBody = p.html,
                plainBody = p.plain,
            ),
        )
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired("LiveJournal sign-in isn't supported")

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.AuthRequired("LiveJournal sign-in isn't supported")

    override suspend fun genres(): FictionResult<List<String>> = FictionResult.Success(emptyList())

    // ─── helpers ──────────────────────────────────────────────────────────

    data class Ref(val journal: String, val tag: String?) {
        fun fictionId() = if (tag == null) "$ID:$journal" else "$ID:$journal~$tag"
    }

    private suspend fun feed(ref: Ref): FictionResult<LjRssParser.Feed> {
        val tagQ = ref.tag?.let { "?tag=" + URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val r = api.get("${api.journalBase(ref.journal)}/data/rss$tagQ", LivejournalApi.XML)
        if (r !is FictionResult.Success) return r as FictionResult.Failure
        val feed = LjRssParser.parse(r.value) ?: return FictionResult.NotFound("LiveJournal: no feed for ${ref.journal}")
        return FictionResult.Success(feed)
    }

    private fun summary(ref: Ref, feed: LjRssParser.Feed) = FictionSummary(
        id = ref.fictionId(),
        sourceId = ID,
        title = if (ref.tag != null) "${feed.title} — ${ref.tag}" else feed.title,
        author = feed.journal.ifBlank { ref.journal },
        description = feed.posts.lastOrNull()?.plain?.take(300) ?: feed.description,
        tags = listOfNotNull(ref.tag),
        chapterCount = feed.posts.size,
        companionSourceUrl = api.journalBase(ref.journal) + (ref.tag?.let { "/tag/$it" }.orEmpty()),
    )

    private fun empty(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page.coerceAtLeast(1), hasNext = false))

    /** Accepts `journal`, `journal/tag`, or a `https://journal.livejournal.com/[tag/x]` URL. */
    internal fun parseInput(raw: String): Ref? {
        val s = raw.trim().removePrefix("http://").removePrefix("https://")
            .replace(Regex("\\s+"), "_")
        if (s.isEmpty()) return null
        val urlMatch = Regex("""^([a-z0-9_-]+)\.livejournal\.com(?:/tag/([^/?#]+))?""", RegexOption.IGNORE_CASE).find(s)
        val (journal, tag) = if (urlMatch != null) {
            urlMatch.groupValues[1] to urlMatch.groupValues[2].ifEmpty { null }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        } else {
            s.substringBefore('/') to s.substringAfter('/', "").ifEmpty { null }
        }
        val j = journal.lowercase().replace('-', '_')
        if (!j.matches(Regex("[a-z0-9_]{1,30}")) || j == "www") return null
        return Ref(j, tag?.trim()?.ifEmpty { null })
    }

    private fun parseId(fictionId: String): Ref? {
        val local = fictionId.removePrefix("$ID:")
        val j = local.substringBefore('~')
        if (!j.matches(Regex("[a-z0-9_]{1,30}"))) return null
        return Ref(j, local.substringAfter('~', "").ifEmpty { null })
    }

    companion object {
        const val ID = "livejournal"
    }
}
