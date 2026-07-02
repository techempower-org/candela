package `in`.jphe.storyvox.source.slack

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.RouteMatch
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.UrlMatcher
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
import `in`.jphe.storyvox.source.slack.config.SlackConfig
import `in`.jphe.storyvox.source.slack.config.SlackDefaults
import `in`.jphe.storyvox.source.slack.net.SlackApi
import `in`.jphe.storyvox.source.slack.net.SlackChannel
import `in`.jphe.storyvox.source.slack.net.SlackMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #454 — Slack as a fiction backend (Web API + Bot Token).
 *
 * **Mapping**
 *
 *  - **Workspace** = the token's scope. One token = one workspace.
 *    Slack's API (unlike Discord's `users/@me/guilds`) doesn't have
 *    a "list of workspaces this token spans" endpoint; the token IS
 *    the workspace selector.
 *  - **Channel** = one fiction. Channel name → fiction title,
 *    channel topic → description, workspace name → "author"
 *    placeholder.
 *  - **Message** = one chapter. Slack's UX strongly favours
 *    one-thought-per-message (vs. Discord's chat-burst pattern), so
 *    v1 doesn't coalesce; a follow-up issue can add Discord-style
 *    same-author coalescing if user feedback indicates it would help.
 *  - **Attachments + files** = filenames + titles surfaced in the
 *    chapter body so TTS narrates them ("Attachment:
 *    dragon-sketch.png", "File: world-map-v2.jpg").
 *  - **Threads** = surfaced as flat messages within the parent
 *    channel for v1 (Discord-parity); each thread reply becomes its
 *    own chapter in the channel timeline. A follow-up could map
 *    thread parents to nested fictions.
 *
 * **Auth**: user-supplied **Bot User OAuth Token** (`xoxb-…`). The
 * user creates a Slack app at api.slack.com, installs it to a
 * workspace they're a member of, copies the Bot User OAuth Token,
 * and pastes it in Settings → Library & Sync → Slack. Read scopes
 * the bot needs: `channels:read`, `channels:history`,
 * `groups:read`, `groups:history`, `users:read`. No bundled default
 * token, no auto-install, no legacy user-token / xoxc cookie
 * automation (banned by Slack ToS). Default OFF on fresh installs
 * — Slack workspaces are private by definition and shouldn't show
 * up in a fresh-install Browse picker.
 *
 * **Fiction ids**: `slack:<channelId>`. **Chapter ids**:
 * `slack:<channelId>::ts-<messageTs>` where `messageTs` is Slack's
 * string-encoded float ts (e.g. `1747340531.123456`). The ts is the
 * canonical message identity in Slack — there's no separate
 * snowflake id like Discord uses.
 *
 * **`supportsFollow = false`**: Slack channels don't have a follow
 * semantic distinct from "the bot is a member". The user already
 * chose to be in the channel (via the install + invite step); a
 * per-channel follow toggle would be UI inconsistency.
 *
 * **`supportsSearch = false` in v1**: Slack's `search.messages`
 * endpoint requires the `search:read` scope which is restricted on
 * certain plan tiers (and not available to bot tokens on free
 * workspaces at all — that endpoint is technically a User Token
 * surface). v1 leaves Search off; a follow-up can add it behind a
 * graceful-degrade empty state per the issue spec.
 */
@SourcePlugin(
    id = SourceIds.SLACK,
    displayName = "Slack",
    // Default OFF on fresh installs — Slack workspaces are private
    // by default and require a token before anything renders. The
    // plugin manager card surfaces the integration so users who
    // want it can enable it from Settings → Plugins.
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Bot-token-authed channel reader · workspace = scope, channel = fiction, message = chapter",
    sourceUrl = "https://slack.com",
)
@Singleton
internal class SlackSource @Inject constructor(
    private val api: SlackApi,
    private val config: SlackConfig,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.SLACK
    override val displayName: String = "Slack"
    override val supportsFollow: Boolean = false

    /** Issue #454 magic-link claim — `<workspace>.slack.com/archives/<CHANNEL>`
     *  (Slack's canonical channel-archive URL shape) or the bare
     *  `slack.com/archives/<CHANNEL>` form. Group 1 captures the
     *  channel id; group 2 captures the optional `/p<TIMESTAMP>`
     *  deep-link to a specific message (unused for routing in v1
     *  but parsed so the regex doesn't reject the link).
     *
     *  We intentionally claim only the archive shape, not the
     *  generic `slack.com/...` host — Slack hosts non-channel URLs
     *  (`slack.com/intl/...`, marketing pages, `app.slack.com/client/...`)
     *  that shouldn't surface as fictions. Confidence is set to
     *  0.9 so a future, more specific Slack matcher (e.g. one
     *  that resolves message permalinks to thread-as-fiction)
     *  could override. */
    override fun matchUrl(url: String): RouteMatch? {
        val m = SLACK_ARCHIVE_URL_PATTERN.matchEntire(url.trim()) ?: return null
        val channelId = m.groupValues[1]
        return RouteMatch(
            sourceId = SourceIds.SLACK,
            fictionId = slackFictionId(channelId),
            confidence = 0.9f,
            label = "Slack channel",
        )
    }

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * Front-page = "channels in this workspace that the bot is a
     * member of". v1 walks `conversations.list` once and renders
     * every is_member=true channel as a fiction summary; pagination
     * within the page-1 fetch chains cursors automatically (a
     * workspace with >200 channels triggers a follow-up
     * `cursor=<…>` call until exhausted).
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Slack bot token not configured. " +
                    "Paste a Bot User OAuth Token (xoxb-…) in " +
                    "Settings → Library & Sync → Slack.",
            )
        }
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val channels = when (val r = fetchAllChannels()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val items = channels
            // Bots can only read history for channels they've been
            // invited to. Non-member channels in the list response
            // are visible-but-unread; filtering them keeps the
            // Browse view honest.
            .filter { it.isMember }
            .sortedBy { it.name.lowercase() }
            .map { it.toSummary(state.workspaceName) }
        return FictionResult.Success(ListPage(items = items, page = 1, hasNext = false))
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Slack doesn't expose a "recently-active channels" ordering
        // distinct from the channel list. Collapse to popular() for
        // v1; activity-based reordering would require N round-trips
        // to fetch the last message per channel.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Slack has no built-in genre/category faceting.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    /**
     * `search.messages` requires `search:read` scope on a User Token
     * (bot tokens can't call it on most plan tiers). v1 returns an
     * empty page so the UI renders the no-results empty state
     * cleanly; the `@SourcePlugin(supportsSearch = false)` annotation
     * keeps the Search tab hidden in Browse. A follow-up can add
     * the endpoint behind the documented graceful-degrade empty
     * state per the issue spec.
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    // ─── detail ────────────────────────────────────────────────────────

    /**
     * One channel's recent history → chapter list. Walks
     * `conversations.history` paginated (cursor-based), reverses to
     * chronological order, filters out system-subtype messages
     * (joins, leaves, pins, etc.), and renders each remaining
     * message as a chapter.
     *
     * The chapter title is the first ~60 chars of the message text,
     * collapsed to a single line, falling back to "Attachment:
     * {name}" / "(empty message)" for media-only or empty posts.
     */
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Slack bot token not configured.",
            )
        }
        val channelId = fictionId.toChannelId()
            ?: return FictionResult.NotFound("Slack fiction id not recognized: $fictionId")

        val channels = when (val r = fetchAllChannels()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val channel = channels.firstOrNull { it.id == channelId }
            ?: return FictionResult.NotFound(
                "Slack channel $channelId not found in workspace (bot may not be a member).",
            )

        val messages = when (val r = fetchAllMessages(channelId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        // Slack returns newest-first; reverse for chronological
        // reading. Filter out system-subtype messages (joins,
        // leaves, pins, etc.) — they don't belong in an audiobook
        // chapter list.
        val chronological = messages.asReversed().filter { it.isUserContentMessage() }

        val chapters = chronological.mapIndexed { idx, msg ->
            ChapterInfo(
                id = chapterIdFor(fictionId, msg.ts),
                sourceChapterId = "ts-${msg.ts}",
                index = idx,
                title = msg.titlePreview(),
                publishedAt = msg.tsMillis(),
            )
        }

        val summary = channel.toSummary(state.workspaceName).copy(
            chapterCount = chapters.size,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    /**
     * One chapter body. Re-fetches the channel's recent history,
     * locates the message whose ts matches the chapter, and renders
     * the plain-text + attachment-filename body.
     *
     * We re-fetch rather than caching because the repository layer
     * owns caching for storyvox; this source is stateless w.r.t.
     * the caller.
     */
    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Slack bot token not configured.",
            )
        }
        val channelId = fictionId.toChannelId()
            ?: return FictionResult.NotFound("Slack fiction id not recognized: $fictionId")
        val messageTs = chapterId.substringAfterLast("::ts-", "")
            .takeIf { it.isNotBlank() }
            ?: return FictionResult.NotFound("Slack chapter id not recognized: $chapterId")

        val messages = when (val r = fetchAllMessages(channelId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val chronological = messages.asReversed().filter { it.isUserContentMessage() }
        val target = chronological.firstOrNull { it.ts == messageTs }
            ?: return FictionResult.NotFound(
                "Slack message $messageTs not in recent history for channel $channelId.",
            )
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = "ts-${target.ts}",
            index = chronological.indexOf(target),
            title = target.titlePreview(),
            publishedAt = target.tsMillis(),
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = target.toHtml(),
                plainBody = target.toPlainText(),
            ),
        )
    }

    // ─── follow ────────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Walk `conversations.list` pages until the cursor is empty (or
     * we hit a safety cap). Slack's pagination is cursor-based; an
     * empty `next_cursor` means "no more pages". Cap at
     * [SlackDefaults.HISTORY_MAX_PAGES] to bound the worst case on
     * massive workspaces.
     */
    private suspend fun fetchAllChannels(): FictionResult<List<SlackChannel>> {
        val collected = mutableListOf<SlackChannel>()
        var cursor = ""
        var pages = 0
        while (pages < SlackDefaults.HISTORY_MAX_PAGES) {
            val page = when (val r = api.listConversations(cursor = cursor)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return r
            }
            collected += page.channels
            val nextCursor = page.responseMetadata?.nextCursor.orEmpty()
            if (nextCursor.isBlank()) break
            cursor = nextCursor
            pages += 1
        }
        return FictionResult.Success(collected)
    }

    /**
     * Walk `conversations.history` pages for one channel until
     * `has_more` is false (or we hit the safety cap). Slack returns
     * messages newest-first; we accumulate in that order and let
     * the caller reverse for chronological rendering.
     */
    private suspend fun fetchAllMessages(channelId: String): FictionResult<List<SlackMessage>> {
        val collected = mutableListOf<SlackMessage>()
        var cursor = ""
        var pages = 0
        while (pages < SlackDefaults.HISTORY_MAX_PAGES) {
            val page = when (val r = api.listMessages(channelId, cursor = cursor)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return r
            }
            collected += page.messages
            val nextCursor = page.responseMetadata?.nextCursor.orEmpty()
            if (!page.hasMore || nextCursor.isBlank()) break
            cursor = nextCursor
            pages += 1
        }
        return FictionResult.Success(collected)
    }

    private fun SlackChannel.toSummary(workspaceName: String): FictionSummary {
        val descriptionText = topic?.value?.takeIf { it.isNotBlank() }
            ?: purpose?.value?.takeIf { it.isNotBlank() }
        return FictionSummary(
            id = slackFictionId(id),
            sourceId = SourceIds.SLACK,
            title = "#${name.ifBlank { id }}",
            author = workspaceName.ifBlank { "Slack" },
            description = descriptionText,
            status = if (isArchived) FictionStatus.COMPLETED else FictionStatus.ONGOING,
        )
    }
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Slack archive-URL pattern. Captures the channel id (group 1) and
 *  optional `/p<TIMESTAMP>` message permalink (group 2). Accepts the
 *  three documented hostname shapes: workspace subdomain
 *  (`<ws>.slack.com`), the bare apex (`slack.com`), and the web-app
 *  shape (`app.slack.com/client/<TEAM>/<CHANNEL>` is intentionally
 *  NOT matched here — that's a different URL grammar and would
 *  require a separate matcher with team-id-aware fiction routing). */
internal val SLACK_ARCHIVE_URL_PATTERN: Regex = Regex(
    """^https?://(?:[\w-]+\.)?slack\.com/archives/([A-Z][A-Z0-9]+)(/p\d+)?(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Stable Slack fiction id from a channel id. */
internal fun slackFictionId(channelId: String): String = "${SourceIds.SLACK}:$channelId"

/** Compose a chapter id from a fiction id + Slack message ts. The
 *  ts is Slack's canonical per-channel message identity — a
 *  string-encoded float seconds-since-epoch like
 *  `"1747340531.123456"`. We keep it as a string in the chapter id
 *  to avoid float-rounding losing the microsecond suffix that
 *  Slack relies on for uniqueness. */
internal fun chapterIdFor(fictionId: String, messageTs: String): String =
    "$fictionId::ts-$messageTs"

/** Decode the channel id from a Slack fiction id, or null when the
 *  id doesn't carry the `slack:` prefix. */
internal fun String.toChannelId(): String? =
    if (startsWith("${SourceIds.SLACK}:")) {
        removePrefix("${SourceIds.SLACK}:").substringBefore("::").takeIf { it.isNotBlank() }
    } else {
        null
    }

/** Slack message subtypes that don't carry user-readable content
 *  (channel joins, leaves, pin events, etc.). Filtered out at the
 *  source layer — they don't belong in an audiobook chapter list.
 *  Empty subtype = regular user message; included by default. */
private val SYSTEM_SUBTYPES: Set<String> = setOf(
    "channel_join",
    "channel_leave",
    "channel_topic",
    "channel_purpose",
    "channel_name",
    "channel_archive",
    "channel_unarchive",
    "pinned_item",
    "unpinned_item",
    "group_join",
    "group_leave",
    "group_topic",
    "group_purpose",
    "group_name",
    "group_archive",
    "group_unarchive",
    "tombstone",
    "reminder_add",
    "reminder_delete",
)

/** True when this message carries user-authored content worth
 *  rendering as a chapter. Filters out system / join / leave /
 *  pin / topic-change messages, but keeps regular user messages
 *  (empty subtype) and `bot_message` (an actual bot post with text). */
internal fun SlackMessage.isUserContentMessage(): Boolean {
    val st = subtype ?: ""
    if (st.isBlank()) return true
    if (st == "bot_message" || st == "thread_broadcast") return true
    return st !in SYSTEM_SUBTYPES
}

/** Build a chapter title from a Slack message: first ~60 chars of
 *  text, newlines collapsed to spaces. Falls back to "Attachment:
 *  filename" for file-only posts, then "(empty message)". */
internal fun SlackMessage.titlePreview(): String {
    val body = text.takeIf { it.isNotBlank() }
    if (body != null) {
        val flat = body.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 60) flat else flat.take(57).trimEnd() + "…"
    }
    val firstFile = files?.firstOrNull()
    if (firstFile != null) {
        val label = firstFile.title?.takeIf { it.isNotBlank() }
            ?: firstFile.name?.takeIf { it.isNotBlank() }
        if (label != null) return "Attachment: $label"
    }
    return "(empty message)"
}

/** Render a Slack message as HTML — text as a `<p>`, attachments
 *  surfaced as `<p>Attachment: …</p>` lines so the reader view +
 *  TTS both mention them. */
internal fun SlackMessage.toHtml(): String {
    val parts = buildList {
        if (text.isNotBlank()) add("<p>${htmlEscape(text)}</p>")
        files?.forEach { f ->
            val label = f.title?.takeIf { it.isNotBlank() }
                ?: f.name?.takeIf { it.isNotBlank() }
            if (label != null) add("<p>Attachment: ${htmlEscape(label)}</p>")
        }
    }
    return parts.joinToString("\n")
}

/** Plain-text projection for TTS. Strips markup; attachments become
 *  natural-language lines so they narrate cleanly. */
internal fun SlackMessage.toPlainText(): String {
    val lines = buildList {
        if (text.isNotBlank()) add(text)
        files?.forEach { f ->
            val label = f.title?.takeIf { it.isNotBlank() }
                ?: f.name?.takeIf { it.isNotBlank() }
            if (label != null) add("Attachment: $label")
        }
    }
    return lines.joinToString("\n\n").trim()
}

/** Parse the Slack ts string into unix milliseconds. Slack's ts is
 *  a string-encoded float seconds-since-epoch (e.g.
 *  `"1747340531.123456"`); converting via Double preserves the
 *  microsecond suffix to within milli precision, which is plenty
 *  for chapter timestamps. Returns null if the string isn't
 *  parseable. */
internal fun SlackMessage.tsMillis(): Long? = ts.toDoubleOrNull()?.let { (it * 1000.0).toLong() }

/** Minimal HTML escape — angle brackets, ampersand, double-quote. */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
