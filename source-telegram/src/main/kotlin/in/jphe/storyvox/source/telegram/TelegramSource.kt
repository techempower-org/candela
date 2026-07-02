package `in`.jphe.storyvox.source.telegram

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
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.telegram.config.TelegramConfig
import `in`.jphe.storyvox.source.telegram.net.TelegramApi
import `in`.jphe.storyvox.source.telegram.net.TelegramMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #462 — Telegram as a fiction backend (Bot API + public
 * channels).
 *
 * **Mapping**
 *
 *  - **Public channel** (`chat.type == "channel"`) = one fiction.
 *    Channel title → fiction title, channel description → fiction
 *    description, @username (when present) → author placeholder.
 *  - **Channel post** = one chapter. Each `channel_post` update
 *    arriving via `getUpdates` becomes a chapter, ordered by
 *    `message.date`.
 *  - **Attachments** = filenames + (for audio) title/performer
 *    surfaced in the chapter body so TTS narrates them
 *    ("Attachment: dragon-sketch.png").
 *
 * **Auth**: user-supplied Bot API token (PAT-style). The user
 * creates a bot via @BotFather inside the Telegram app,
 * receives a token like `123456:ABC-DEF...`, pastes it in
 * Settings → Library & Sync → Telegram. The bot has to be added
 * as a member of any public channel the user wants storyvox to
 * read (admin invite, normal channel member). No bundled default
 * token, no auto-join, no MTProto user-side path (private DMs /
 * private groups deferred). Default ON in the plugin chip but
 * inert until a token is configured — same shape as Discord.
 *
 * **Fiction ids**: `telegram:<channelId>` (channel ids are
 * negative integers like `-1001234567890` for channels; positive
 * for groups/DMs which v1 doesn't surface). **Chapter ids**:
 * `telegram:<channelId>::msg-<messageId>`.
 *
 * **`supportsFollow = false`**: Telegram channels don't have a
 * follow semantic distinct from "the bot is a member". The user
 * already chose to be in the channel (via the bot invite); a
 * per-channel follow toggle would be surface inconsistency.
 *
 * **`supportsSearch = false`**: Bot API has no full-text search
 * endpoint. Search is deferred to a sibling issue if/when an
 * MTProto user-side integration lands.
 *
 * ## Architectural constraint: bot-after-invite history only
 *
 * The Bot API gives bots **no access to message history that
 * predates the bot's invitation** to a channel. There is no
 * `getChatHistory` for bots; the only way to receive channel
 * posts is `getUpdates`, which delivers events as they arrive.
 * v1 accepts this: each Browse refresh polls `getUpdates`,
 * accumulates observed `channel_post` events keyed by
 * `chat.id`, and exposes the accumulated set as chapters.
 *
 * Practical consequence: the first session after inviting the
 * bot shows zero chapters until the channel admin posts something
 * new. The Settings card explains this with a clarifying line so
 * the empty state isn't mystery-meat.
 *
 * In-memory accumulation (the [observedPosts] map) means
 * chapters reset on process restart. A follow-up issue could
 * persist observed posts to Room so the chapter list survives
 * cold launches; v1 ships memory-only to keep the scope
 * contained.
 */
@SourcePlugin(
    id = SourceIds.TELEGRAM,
    displayName = "Telegram",
    // Same default posture as Discord (#403 / #436): the chip is
    // discoverable on fresh install, but the backend stays inert
    // until the user provides a bot token.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Bot-token-authed public-channel reader · channel = fiction, post = chapter",
    sourceUrl = "https://telegram.org",
)
@Singleton
internal class TelegramSource @Inject constructor(
    private val api: TelegramApi,
    private val config: TelegramConfig,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.TELEGRAM
    override val displayName: String = "Telegram"
    override val supportsFollow: Boolean = false

    /**
     * In-memory record of observed channel posts, keyed by
     * channel id. Populated by [refreshUpdates] each time the
     * Browse list or a chapter list is requested. Maps id →
     * (chronological list of messages).
     *
     * Concurrent: the source is a singleton and `getUpdates`
     * polls can interleave across coroutines. The map and the
     * inner lists are guarded by per-channel lock-free swap —
     * we read-modify-write the whole list via
     * [putChannelMessages] which serialises via
     * [observedPostsLock]. Read paths just snapshot the current
     * value.
     */
    private val observedPosts: ConcurrentHashMap<Long, List<TelegramMessage>> = ConcurrentHashMap()
    private val observedPostsLock = Any()

    /**
     * Highest `update_id` we've observed so far. The Bot API
     * silently discards updates with `update_id <= offset - 1`,
     * so we can use this to mark old updates seen and shrink the
     * `getUpdates` response window. Default 0 = "give me
     * everything you have".
     */
    private val lastUpdateId: AtomicLong = AtomicLong(0L)

    /** Issue #472 — `t.me/<channel>` or `telegram.me/<channel>` URL. */
    override fun matchUrl(url: String): RouteMatch? {
        val m = TELEGRAM_URL_PATTERN.matchEntire(url.trim()) ?: return null
        // The URL contains the @-handle, not the numeric chat id.
        // We can't resolve handle → id without a getChat call (which
        // requires the token), so the match is low-confidence and
        // the resolved fictionId is the handle-shaped id. The actual
        // fiction lookup will resolve via the Browse list, which
        // does carry both forms.
        return RouteMatch(
            sourceId = SourceIds.TELEGRAM,
            fictionId = "telegram:@${m.groupValues[1]}",
            confidence = 0.6f,
            label = "Telegram channel",
        )
    }

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * "Channels the bot has seen activity in", one fiction per
     * observed `channel_post.chat.id`. Each Browse refresh hits
     * `getUpdates` first to pick up any new channel activity,
     * then renders the deduplicated chat list from
     * [observedPosts].
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Telegram bot token not configured. " +
                    "Paste a token in Settings → Library & Sync → Telegram.",
            )
        }
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        // Refresh the observed-channels record. Failures here are
        // non-fatal — we still render whatever we've already
        // accumulated this session.
        refreshUpdates()
        val channels = observedPosts.keys.toList()
        // Pull channel metadata for each observed channel so we
        // can render the friendly title + description. getChat
        // failures (channel deleted, bot kicked) silently fall
        // through to a placeholder.
        val items = channels.mapNotNull { chatId ->
            val chat = when (val r = api.getChat(chatId)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return@mapNotNull null
            }
            // v1 only surfaces broadcast channels; private chats,
            // groups, supergroups are skipped at the source layer.
            if (chat.type != "channel") return@mapNotNull null
            FictionSummary(
                id = telegramFictionId(chatId),
                sourceId = SourceIds.TELEGRAM,
                title = chat.title.ifBlank { "Telegram channel" },
                author = chat.username?.let { "@$it" } ?: "Telegram",
                description = chat.description?.ifBlank { null },
                status = FictionStatus.ONGOING,
            )
        }.sortedBy { it.title.lowercase() }
        return FictionResult.Success(ListPage(items = items, page = 1, hasNext = false))
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // No separate "recently active" ordering for v1 — same shape
        // as popular() because both surfaces are derived from the
        // observed-posts record. A follow-up could sort by the
        // most-recent observed message timestamp.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Telegram has no built-in genre / category faceting.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    /**
     * Search is deferred per the issue spec — Bot API has no
     * full-text search endpoint. Return an empty page so the UI
     * renders the no-results empty state cleanly.
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    // ─── detail ────────────────────────────────────────────────────────

    /**
     * Channel detail: get the channel metadata + render every
     * observed post as a chapter, ordered by `message.date`.
     *
     * Refreshes [observedPosts] first so a freshly-arrived post
     * shows up immediately if the user opened FictionDetail
     * without first hitting Browse.
     */
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Telegram bot token not configured.",
            )
        }
        val chatId = fictionId.toChatId()
            ?: return FictionResult.NotFound("Telegram fiction id not recognized: $fictionId")

        refreshUpdates()

        val chat = when (val r = api.getChat(chatId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        if (chat.type != "channel") {
            return FictionResult.NotFound(
                "Telegram chat $chatId is not a public channel (type=${chat.type}). " +
                    "v1 only surfaces broadcast channels.",
            )
        }

        val posts = observedPosts[chatId]?.sortedBy { it.date }.orEmpty()
        val chapters = posts.mapIndexed { idx, msg ->
            ChapterInfo(
                id = chapterIdFor(fictionId, msg.messageId),
                sourceChapterId = "msg-${msg.messageId}",
                index = idx,
                title = msg.titlePreview(),
                publishedAt = msg.date * 1_000L,
            )
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.TELEGRAM,
            title = chat.title.ifBlank { "Telegram channel" },
            author = chat.username?.let { "@$it" } ?: "Telegram",
            description = chat.description?.ifBlank { null },
            status = FictionStatus.ONGOING,
            chapterCount = chapters.size,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    /**
     * One chapter body. Looks up the message by id in
     * [observedPosts]; if the bot has been restarted since the
     * post arrived, the observed-posts map is empty and the
     * caller sees `NotFound` (the documented v1 limitation).
     */
    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val state = config.current()
        if (state.apiToken.isBlank()) {
            return FictionResult.AuthRequired(
                "Telegram bot token not configured.",
            )
        }
        val chatId = fictionId.toChatId()
            ?: return FictionResult.NotFound("Telegram fiction id not recognized: $fictionId")
        val messageId = chapterId.substringAfterLast("::msg-", "")
            .takeIf { it.isNotBlank() }
            ?.toLongOrNull()
            ?: return FictionResult.NotFound("Telegram chapter id not recognized: $chapterId")

        refreshUpdates()

        val posts = observedPosts[chatId].orEmpty().sortedBy { it.date }
        val msg = posts.firstOrNull { it.messageId == messageId }
            ?: return FictionResult.NotFound(
                "Telegram message $messageId not in observed history for channel $chatId. " +
                    "Bots only see posts that arrived after they joined the channel — " +
                    "older posts cannot be replayed via the Bot API.",
            )
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = "msg-${msg.messageId}",
            index = posts.indexOf(msg),
            title = msg.titlePreview(),
            publishedAt = msg.date * 1_000L,
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = msg.toHtml(),
                plainBody = msg.toPlainText(),
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
     * Hit `getUpdates`, sift `channel_post` events into
     * [observedPosts], and bump [lastUpdateId] so future polls
     * narrow the response window.
     *
     * Network / auth failures here are silent — the caller is
     * always reading from the observed-posts cache; refresh just
     * tries to keep it current.
     */
    private suspend fun refreshUpdates() {
        val offset = lastUpdateId.get() + 1
        val updates = when (val r = api.getUpdates(offset)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return
        }
        if (updates.isEmpty()) return
        var maxSeen = lastUpdateId.get()
        for (u in updates) {
            if (u.updateId > maxSeen) maxSeen = u.updateId
            val post = u.channelPost ?: u.editedChannelPost ?: continue
            val chatId = post.chat.id
            putChannelMessage(chatId, post)
        }
        lastUpdateId.set(maxSeen)
    }

    /**
     * Append (or replace, on edits) a message in the
     * observed-posts record for one channel. Replacement happens
     * when the same `message_id` arrives again (edit case);
     * append happens otherwise.
     *
     * Serialised through [observedPostsLock] so concurrent
     * Browse + Detail refreshes don't race on the same channel's
     * list mutation.
     */
    private fun putChannelMessage(chatId: Long, msg: TelegramMessage) {
        synchronized(observedPostsLock) {
            val existing = observedPosts[chatId].orEmpty()
            val without = existing.filter { it.messageId != msg.messageId }
            observedPosts[chatId] = without + msg
        }
    }

    companion object {
        /** Internal test hook — clear the in-memory observed-posts
         *  cache. Production code doesn't call this; tests use it
         *  to start each scenario from a clean state. */
        internal fun clearObservedPosts(source: TelegramSource) {
            source.observedPosts.clear()
            source.lastUpdateId.set(0L)
        }
    }
}

// ─── helpers ──────────────────────────────────────────────────────────

/** Issue #472 — Telegram channel URL pattern. Captures the @-handle. */
internal val TELEGRAM_URL_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?(?:t|telegram)\.me/(\w+)(?:/.*)?(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Stable Telegram fiction id from a numeric channel id. */
internal fun telegramFictionId(chatId: Long): String = "telegram:$chatId"

/** Compose a chapter id from a fiction id + Telegram message id. */
internal fun chapterIdFor(fictionId: String, messageId: Long): String =
    "$fictionId::msg-$messageId"

/** Decode the channel id from a Telegram fiction id, or null when the
 *  id doesn't carry a numeric `telegram:N` form. The handle form
 *  (`telegram:@username`) returns null — those require an additional
 *  resolve step via `getChat?chat_id=@username`. */
internal fun String.toChatId(): Long? {
    if (!startsWith("telegram:")) return null
    val core = removePrefix("telegram:").substringBefore("::")
    return core.toLongOrNull()
}

/** Build a chapter title from a Telegram message: first ~60 chars of
 *  text/caption, with newlines collapsed to spaces. Falls back to
 *  "Attachment: filename" / "Audio: title" / "(empty post)" for
 *  media-only posts. */
internal fun TelegramMessage.titlePreview(): String {
    val body = (text ?: caption)?.takeIf { it.isNotBlank() }
    if (body != null) {
        val flat = body.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 60) flat else flat.take(57).trimEnd() + "…"
    }
    document?.fileName?.let { if (it.isNotBlank()) return "Attachment: $it" }
    audio?.let { a ->
        val t = a.title?.takeIf { it.isNotBlank() }
        val p = a.performer?.takeIf { it.isNotBlank() }
        if (t != null && p != null) return "Audio: $p — $t"
        if (t != null) return "Audio: $t"
    }
    video?.fileName?.let { if (it.isNotBlank()) return "Video: $it" }
    if (!photo.isNullOrEmpty()) return "Photo post"
    if (document != null) return "File post"
    if (video != null) return "Video post"
    if (audio != null) return "Audio post"
    return "(empty post)"
}

/** Render a Telegram message as HTML — text/caption as a `<p>`,
 *  attachments surfaced as `<p>Attachment: …</p>` lines so the
 *  reader view + TTS both mention them. */
internal fun TelegramMessage.toHtml(): String {
    val parts = buildList {
        val body = (text ?: caption)?.takeIf { it.isNotBlank() }
        if (body != null) add("<p>${htmlEscape(body)}</p>")
        document?.fileName?.let { if (it.isNotBlank()) add("<p>Attachment: ${htmlEscape(it)}</p>") }
        audio?.let { a ->
            val parts2 = listOfNotNull(a.performer, a.title)
            if (parts2.isNotEmpty()) add("<p>Audio: ${htmlEscape(parts2.joinToString(" — "))}</p>")
        }
        video?.fileName?.let { if (it.isNotBlank()) add("<p>Video: ${htmlEscape(it)}</p>") }
        if (!photo.isNullOrEmpty()) add("<p>Photo attached</p>")
    }
    return parts.joinToString("\n")
}

/** Plain-text projection for TTS. Strips markup; attachments become
 *  natural-language lines so they narrate cleanly. */
internal fun TelegramMessage.toPlainText(): String {
    val lines = buildList {
        val body = (text ?: caption)?.takeIf { it.isNotBlank() }
        if (body != null) add(body)
        document?.fileName?.let { if (it.isNotBlank()) add("Attachment: $it") }
        audio?.let { a ->
            val parts = listOfNotNull(a.performer, a.title)
            if (parts.isNotEmpty()) add("Audio: ${parts.joinToString(" — ")}")
        }
        video?.fileName?.let { if (it.isNotBlank()) add("Video: $it") }
        if (!photo.isNullOrEmpty()) add("Photo attached")
    }
    return lines.joinToString("\n\n").trim()
}

/** Minimal HTML escape — angle brackets, ampersand, double-quote. */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
