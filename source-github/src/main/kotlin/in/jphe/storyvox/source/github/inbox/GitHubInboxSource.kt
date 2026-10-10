package `in`.jphe.storyvox.source.github.inbox

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.filter.FilterDimension
import `in`.jphe.storyvox.data.source.filter.FilterState
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
import `in`.jphe.storyvox.source.github.auth.GitHubAuthRepository
import `in`.jphe.storyvox.source.github.auth.GitHubSession
import `in`.jphe.storyvox.source.github.inbox.InboxNarration.ThreadRef
import `in`.jphe.storyvox.source.github.render.MarkdownChapterRenderer
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1470 — GitHub PR / notification narrator.
 *
 * A second `@SourcePlugin` in `:source-github` (same shape as
 * `:source-notion` registering two) that turns the signed-in user's
 * GitHub work queue into listenable "fictions":
 *
 *  - **Inbox** (`popular`) — `GET /notifications`, PR + issue threads only.
 *    Needs the `notifications` OAuth scope, added to the default sign-in
 *    scopes by #1470; tokens granted earlier get AuthRequired with a
 *    "sign in again" message while the search-backed views keep working.
 *  - **Reviews** (`latestUpdates`) — open PRs awaiting the user's review.
 *  - **Show** filter / `byGenre` — involving me, mentions, assigned, my PRs.
 *  - **Search** — `involves:@me` + the typed term.
 *
 * Each thread is a fiction `github-inbox:owner/repo/pull/12` with two
 * chapters: an **Overview** (title, author, state, branch + diff size,
 * labels, CI check summary, description) and the **Conversation** (the
 * [InboxNarration.RECENT_COMMENTS] newest issue comments, reviews and
 * inline review comments). [latestRevisionToken] is the thread's
 * `updated_at`, so a followed PR re-narrates when someone comments.
 *
 * Auth: reuses the Device-Flow token via the shared authed OkHttp client
 * ([GitHubInboxApi]); a signed-out user gets [FictionResult.AuthRequired]
 * before any network call.
 */
@SourcePlugin(
    id = GitHubInboxSource.SOURCE_ID,
    displayName = "GitHub inbox",
    // Auth-only: an off-by-default chip avoids a fresh-install Browse tab
    // that can only say "sign in". Users enable it in Settings → Plugins.
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Your GitHub notifications, review requests and mentions, read aloud · needs GitHub sign-in",
    sourceUrl = "https://github.com/notifications",
    chipLabel = "GitHub inbox",
    searchHint = "Search pull requests and issues you're involved in",
)
@Singleton
internal class GitHubInboxSource @Inject constructor(
    private val api: GitHubInboxApi,
    private val auth: GitHubAuthRepository,
    private val renderer: MarkdownChapterRenderer,
) : FictionSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "GitHub inbox"

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        requireSignedIn()?.let { return it }
        return when (val r = api.notifications(page)) {
            is FictionResult.Success -> FictionResult.Success(
                ListPage(
                    items = r.value.mapNotNull(::notificationSummary),
                    page = page,
                    // Raw page size decides paging — filtering out
                    // Release/CheckSuite threads must not end the list early.
                    hasNext = r.value.size >= GitHubInboxApi.PER_PAGE,
                ),
            )
            is FictionResult.Failure -> r
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        searchThreads(View.REVIEWS.qualifier + OPEN, page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        val view = View.entries.firstOrNull { it.label == genre || it.id == genre }
            ?: return FictionResult.NotFound("Unknown GitHub inbox view: $genre")
        return searchThreads(view.qualifier + OPEN, page)
    }

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(View.entries.map { it.label })

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim()
        // applyFilters() composes a `@me` view qualifier into the term; a
        // bare typed term is scoped to threads the user is involved in so
        // Search never turns into a firehose of all of GitHub.
        val q = if ("@me" in term) term else "${View.INVOLVED.qualifier} $term".trim()
        return searchThreads(q, query.page.coerceAtLeast(1))
    }

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Sort(
            key = FILTER_VIEW,
            label = "Show",
            options = View.entries.map { FilterDimension.SortOption(it.id, it.label) },
        ),
        FilterDimension.Toggle(key = FILTER_INCLUDE_CLOSED, label = "Include closed"),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        val view = state.stringVal(FILTER_VIEW)
            ?.let { id -> View.entries.firstOrNull { it.id == id } }
            ?: View.INVOLVED
        val parts = buildList {
            base.term.trim().takeIf { it.isNotEmpty() }?.let(::add)
            add(view.qualifier)
            if (state.boolVal(FILTER_INCLUDE_CLOSED) != true) add(OPEN.trim())
        }
        return base.copy(term = parts.joinToString(" "))
    }

    private suspend fun searchThreads(q: String, page: Int): FictionResult<ListPage<FictionSummary>> {
        requireSignedIn()?.let { return it }
        return when (val r = api.searchIssues("$q archived:false", page)) {
            is FictionResult.Success -> FictionResult.Success(
                ListPage(
                    items = r.value.items.mapNotNull(::issueSummary),
                    page = page,
                    hasNext = r.value.items.size >= GitHubInboxApi.PER_PAGE &&
                        page * GitHubInboxApi.PER_PAGE < minOf(r.value.totalCount, SEARCH_CAP),
                ),
            )
            is FictionResult.Failure -> r
        }
    }

    // ─── detail + chapters ─────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        requireSignedIn()?.let { return it }
        val ref = refOf(fictionId) ?: return malformed(fictionId)
        if (ref.kind == ThreadRef.Kind.Release) return releaseDetail(ref, fictionId)
        val issue = when (val r = api.issue(ref.owner, ref.repo, ref.number)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val summary = issueSummary(issue)
            ?: summaryFor(ref, issue.title, issue.user?.login, issue.htmlUrl, tags = emptyList())
        val chapters = listOf(
            ChapterInfo(
                id = "$fictionId:$CH_OVERVIEW",
                sourceChapterId = CH_OVERVIEW,
                index = 0,
                title = "Overview",
            ),
            ChapterInfo(
                id = "$fictionId:$CH_CONVERSATION",
                sourceChapterId = CH_CONVERSATION,
                index = 1,
                title = "Conversation",
                publishedAt = issue.updatedAt.epochMillis(),
            ),
        )
        return FictionResult.Success(
            FictionDetail(
                summary = summary.copy(chapterCount = chapters.size),
                chapters = chapters,
                genres = issue.labels.map { it.name }.filter { it.isNotBlank() },
                lastUpdatedAt = issue.updatedAt.epochMillis(),
                authorId = issue.user?.login,
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        requireSignedIn()?.let { return it }
        val ref = refOf(fictionId) ?: return malformed(fictionId)
        return when (chapterId.substringAfterLast(':')) {
            CH_RELEASE_NOTES -> if (ref.kind == ThreadRef.Kind.Release) releaseNotes(ref, fictionId)
                else FictionResult.NotFound("Unknown chapter: $chapterId")
            CH_OVERVIEW -> overview(ref, fictionId)
            CH_CONVERSATION -> conversation(ref, fictionId)
            else -> FictionResult.NotFound("Unknown chapter: $chapterId")
        }
    }

    private suspend fun overview(ref: ThreadRef, fictionId: String): FictionResult<ChapterContent> = coroutineScope {
        val issueD = async { api.issue(ref.owner, ref.repo, ref.number) }
        val pullD = if (ref.isPull) async { api.pull(ref.owner, ref.repo, ref.number) } else null
        val issue = when (val r = issueD.await()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return@coroutineScope r
        }
        // PR extras are best-effort: a failed /pulls or /check-runs call
        // drops that sentence rather than the whole chapter.
        val pull = pullD?.await()?.valueOrNull()
        val checks = pull?.head?.sha?.takeIf { it.isNotBlank() }?.let { sha ->
            api.checkRuns(ref.owner, ref.repo, sha).valueOrNull()?.checkRuns
        }
        val info = ChapterInfo(
            id = "$fictionId:$CH_OVERVIEW",
            sourceChapterId = CH_OVERVIEW,
            index = 0,
            title = "Overview",
        )
        // The chapter is titled "Overview", so its first line is the PR's title,
        // not a repeat of the chapter title: keep it in the narration.
        FictionResult.Success(
            renderer.render(
                info,
                InboxNarration.overviewMarkdown(ref, issue, pull, checks),
                stripLeadingTitle = false,
            ),
        )
    }

    private suspend fun conversation(ref: ThreadRef, fictionId: String): FictionResult<ChapterContent> = coroutineScope {
        val issue = when (val r = api.issue(ref.owner, ref.repo, ref.number)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return@coroutineScope r
        }
        // The endpoints list oldest-first; jump to the LAST page so a
        // 300-comment thread narrates its newest comments, not its first.
        val commentsD = async {
            api.issueComments(ref.owner, ref.repo, ref.number, lastPage(issue.comments))
        }
        val pullD = if (ref.isPull) async { api.pull(ref.owner, ref.repo, ref.number) } else null
        val reviewsD = if (ref.isPull) async { api.reviews(ref.owner, ref.repo, ref.number) } else null
        val comments = when (val r = commentsD.await()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return@coroutineScope r
        }
        val pull = pullD?.await()?.valueOrNull()
        val reviews = reviewsD?.await()?.valueOrNull().orEmpty()
        val inline = pull?.takeIf { it.reviewComments > 0 }?.let {
            api.reviewComments(ref.owner, ref.repo, ref.number, lastPage(it.reviewComments)).valueOrNull()
        }.orEmpty()
        val entries = comments.mapNotNull(InboxNarration::commentEntry) +
            reviews.mapNotNull(InboxNarration::reviewEntry) +
            inline.mapNotNull(InboxNarration::commentEntry)
        val total = issue.comments + (pull?.reviewComments ?: 0) +
            reviews.count { InboxNarration.reviewEntry(it) != null }
        val info = ChapterInfo(
            id = "$fictionId:$CH_CONVERSATION",
            sourceChapterId = CH_CONVERSATION,
            index = 1,
            title = "Conversation",
            publishedAt = issue.updatedAt.epochMillis(),
        )
        FictionResult.Success(renderer.render(info, InboxNarration.conversationMarkdown(entries, total)))
    }

    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        requireSignedIn()?.let { return it }
        val ref = refOf(fictionId) ?: return malformed(fictionId)
        if (ref.kind == ThreadRef.Kind.Release) {
            return when (val r = api.release(ref.owner, ref.repo, ref.number)) {
                is FictionResult.Success -> FictionResult.Success(r.value.publishedAt)
                is FictionResult.Failure -> r
            }
        }
        return when (val r = api.issue(ref.owner, ref.repo, ref.number)) {
            is FictionResult.Success -> FictionResult.Success(r.value.updatedAt)
            is FictionResult.Failure -> r
        }
    }

    // ─── releases (#1841) ──────────────────────────────────────────────

    private fun releaseNotesInfo(fictionId: String, release: GhRelease) = ChapterInfo(
        id = "$fictionId:$CH_RELEASE_NOTES",
        sourceChapterId = CH_RELEASE_NOTES,
        index = 0,
        title = "Release notes",
        publishedAt = release.publishedAt.epochMillis(),
    )

    private suspend fun releaseDetail(ref: ThreadRef, fictionId: String): FictionResult<FictionDetail> {
        val release = when (val r = api.release(ref.owner, ref.repo, ref.number)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val title = release.name?.takeIf { it.isNotBlank() } ?: release.tagName
        val chapters = listOf(releaseNotesInfo(fictionId, release))
        val summary = summaryFor(
            ref = ref,
            title = title,
            author = release.author?.login,
            htmlUrl = release.htmlUrl,
            tags = listOf("Release"),
            description = "Release ${release.tagName} in ${ref.repoFullName}",
            status = FictionStatus.COMPLETED,
        )
        return FictionResult.Success(
            FictionDetail(
                summary = summary.copy(chapterCount = chapters.size),
                chapters = chapters,
                genres = emptyList(),
                lastUpdatedAt = release.publishedAt.epochMillis(),
                authorId = release.author?.login,
            ),
        )
    }

    private suspend fun releaseNotes(ref: ThreadRef, fictionId: String): FictionResult<ChapterContent> {
        val release = when (val r = api.release(ref.owner, ref.repo, ref.number)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        // The chapter is titled "Release notes", so the release name leads.
        return FictionResult.Success(
            renderer.render(
                releaseNotesInfo(fictionId, release),
                InboxNarration.releaseMarkdown(ref, release),
                stripLeadingTitle = false,
            ),
        )
    }

    // ─── follow (not supported — Library add is the "follow") ─────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page = page, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.NotFound("GitHub inbox threads can't be followed remotely")

    // ─── mapping ───────────────────────────────────────────────────────

    private fun notificationSummary(n: GhNotification): FictionSummary? {
        // CheckSuite / Discussion / Commit threads have nothing to narrate
        // yet; Releases narrate their notes (#1841).
        if (n.subject.type !in NARRATED_SUBJECT_TYPES) return null
        val ref = InboxNarration.parseApiUrl(n.subject.url) ?: return null
        val tags = buildList {
            add(InboxNarration.reasonLabel(n.reason))
            add(InboxNarration.kindLabel(ref.kind))
            if (n.unread) add("Unread")
        }
        return summaryFor(
            ref = ref,
            title = n.subject.title,
            author = null,
            htmlUrl = null,
            tags = tags,
            description = "${InboxNarration.reasonLabel(n.reason)} · " +
                if (ref.kind == ThreadRef.Kind.Release) "release in ${ref.repoFullName}"
                else "${InboxNarration.kindLabel(ref.kind).lowercase()} ${ref.number} in ${ref.repoFullName}",
        )
    }

    private fun issueSummary(issue: GhIssue): FictionSummary? {
        val ref = InboxNarration.refFor(issue) ?: return null
        val kind = InboxNarration.kindLabel(ref.isPull)
        return summaryFor(
            ref = ref,
            title = issue.title,
            author = issue.user?.login,
            htmlUrl = issue.htmlUrl,
            tags = listOf(kind) + issue.labels.map { it.name }.filter { it.isNotBlank() },
            description = "$kind ${ref.number} in ${ref.repoFullName}" +
                (issue.user?.login?.let { " · by $it" } ?: "") +
                " · ${plural(issue.comments, "comment")}",
            status = if (issue.state == "closed") FictionStatus.COMPLETED else FictionStatus.ONGOING,
        )
    }

    private fun summaryFor(
        ref: ThreadRef,
        title: String,
        author: String?,
        htmlUrl: String?,
        tags: List<String>,
        description: String? = null,
        status: FictionStatus = FictionStatus.ONGOING,
    ) = FictionSummary(
        id = "$SOURCE_ID:${ref.localId}",
        sourceId = SOURCE_ID,
        title = title.ifBlank { "${InboxNarration.kindLabel(ref.kind)} ${ref.number}" },
        // The repo reads as the "author" in Library rows — it's what the
        // listener needs to place the thread; the opener is in the description.
        author = ref.repoFullName.ifBlank { author.orEmpty() },
        description = description,
        tags = tags,
        status = status,
        chapterCount = 2,
        companionSourceUrl = htmlUrl ?: "https://github.com/${ref.localId}",
    )

    private fun refOf(fictionId: String): ThreadRef? =
        InboxNarration.parseLocalId(fictionId.removePrefix("$SOURCE_ID:"))

    private fun malformed(fictionId: String) =
        FictionResult.NotFound("Not a GitHub inbox thread id: $fictionId")

    private fun requireSignedIn(): FictionResult.AuthRequired? =
        if (auth.sessionState.value is GitHubSession.Authenticated) null
        else FictionResult.AuthRequired("Sign in to GitHub in Settings to hear your inbox")

    private fun <T> FictionResult<T>.valueOrNull(): T? = (this as? FictionResult.Success<T>)?.value

    private fun plural(n: Int, noun: String) = InboxNarration.plural(n, noun)

    private fun String?.epochMillis(): Long? =
        this?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /** Views over `/search/issues`; [qualifier] is composed into the query. */
    internal enum class View(val id: String, val label: String, val qualifier: String) {
        INVOLVED("involved", "Involving me", "involves:@me"),
        REVIEWS("reviews", "Review requests", "is:pr review-requested:@me"),
        MENTIONS("mentions", "Mentions", "mentions:@me"),
        ASSIGNED("assigned", "Assigned to me", "assignee:@me"),
        MY_PRS("mine", "My pull requests", "is:pr author:@me"),
    }

    companion object {
        /** The @SourcePlugin id — the single source of truth (no SourceIds entry). */
        const val SOURCE_ID: String = "github-inbox"

        const val CH_OVERVIEW: String = "overview"
        const val CH_CONVERSATION: String = "conversation"
        const val CH_RELEASE_NOTES: String = "notes"

        /** Notification subject types the inbox narrates (#1841 adds Release). */
        private val NARRATED_SUBJECT_TYPES = setOf("PullRequest", "Issue", "Release")

        internal const val FILTER_VIEW = "view"
        internal const val FILTER_INCLUDE_CLOSED = "includeClosed"

        private const val OPEN = " is:open"

        /** `/search/issues` serves at most 1000 results. */
        private const val SEARCH_CAP = 1000

        internal fun lastPage(count: Int): Int =
            if (count <= 0) 1 else (count + GitHubInboxApi.COMMENT_PAGE - 1) / GitHubInboxApi.COMMENT_PAGE
    }
}
