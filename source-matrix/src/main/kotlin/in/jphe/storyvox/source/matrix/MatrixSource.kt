package `in`.jphe.storyvox.source.matrix

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
import `in`.jphe.storyvox.source.matrix.config.MatrixConfig
import `in`.jphe.storyvox.source.matrix.net.MatrixApi
import `in`.jphe.storyvox.source.matrix.net.MatrixEvent
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #457 — Matrix as a fiction backend.
 *
 * **Mapping**
 *
 *  - **Homeserver + access token** = scope. The user pastes their
 *    homeserver URL (e.g. `https://matrix.org`) and an access token
 *    (`syt_…` / `mat_…`) in Settings. One token = one user's view
 *    across rooms they've joined on that homeserver.
 *  - **Joined room** = one fiction. Room name → fiction title, room
 *    topic → description, the user's own `@handle:server` →
 *    placeholder author.
 *  - **`m.room.message` event** = one chapter, OR consecutive
 *    same-sender events within a tight time window (default 5 min,
 *    configurable 1-30 min) get coalesced into one chapter — same
 *    chat-thread-as-episode shape as Discord (#403) and Slack (#454).
 *  - **Media events** (`m.image`, `m.file`, `m.video`, `m.audio`)
 *    surface the filename inline as a `"Attachment: dragon-sketch.png"`
 *    line so TTS narrates them. v1 doesn't fetch the bytes.
 *  - **Threads** (`m.thread` relation type, Matrix spec v1.7+) are
 *    flat-within-room for v1, same as Discord defaults. Parent and
 *    thread events surface as siblings in the chapter list.
 *
 * **Auth**: user-supplied **access token** + **homeserver URL**.
 * The user creates an access token via their homeserver's Element
 * / web client → Settings → Help & About → Advanced → "Access
 * Token", pastes both into Settings → Library & Sync → Matrix.
 * No password login (worse posture — storyvox would hold a
 * recoverable secret), no SSO/OIDC (deferred to v2; would need a
 * WebView OIDC flow). Default OFF on fresh installs per the issue
 * spec's `defaultEnabled=false` acceptance.
 *
 * **Fiction ids**: `matrix:<roomIdEncoded>` where `roomIdEncoded` is
 * the URL-encoded `!opaqueId:server.tld` Matrix room id (the
 * percent-encoding hides the `:` and `!` characters from the
 * fiction-id parser's `::` separator + lets the id round-trip
 * through DataStore / Room without escaping headaches).
 *
 * **Chapter ids**: `matrix:<roomIdEncoded>::event-<eventId>` where
 * `eventId` is the `$base64hash` Matrix event id from the
 * `event_id` field. Event ids are opaque strings; they don't need
 * additional encoding because Matrix already restricts them to
 * URL-safe characters.
 *
 * **`supportsFollow = false`**: Matrix rooms don't have a "follow"
 * semantic distinct from "be joined to the room". The user already
 * chose to join the room (in their Element / Matrix client); a
 * per-room follow toggle would be a surface inconsistency.
 *
 * **Federation note (v1 simplification)**: a Matrix room can have
 * members from many homeservers (`@alice:matrix.org` and
 * `@bob:fosdem.org` in the same room). The user-resolution lookup
 * for sender display names hits the user's home homeserver in
 * principle, but v1 routes every lookup through the configured
 * homeserver — homeservers cache federated profile data they've
 * seen, so this works in practice. A v2 enhancement could parse
 * the homeserver suffix and dispatch per-server (with the cost of
 * a multi-host OkHttp pool).
 */
@SourcePlugin(
    id = SourceIds.MATRIX,
    displayName = "Matrix",
    // Per issue acceptance criteria — chip stays hidden on fresh
    // installs until the user opts in via the Plugin Manager
    // (#404). Matches the issue spec's defaultEnabled=false.
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Federated open-standard chat · room = fiction, message = chapter (with same-sender coalescing)",
    sourceUrl = "https://matrix.org",
)
@Singleton
internal class MatrixSource @Inject constructor(
    private val api: MatrixApi,
    private val config: MatrixConfig,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.MATRIX
    override val displayName: String = "Matrix"
    override val supportsFollow: Boolean = false

    /**
     * Per-process cache of `@user:homeserver` → display name lookups.
     * Matrix profile fetches are federation hops on the homeserver
     * side (potentially expensive when the user lives on a different
     * homeserver than the room's), so we resolve each id at most
     * once per process lifetime. The map is cleared on
     * [clearCaches] which the test harness calls between
     * scenarios.
     *
     * Stored value `null` is a sentinel for "we looked, the user
     * has no displayname set" so we don't re-fetch on every chapter
     * render — matches the Discord username-fallback shape.
     */
    private val displayNameCache: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    /**
     * Issue #472 — claim Matrix URLs for the magic-link paste flow.
     *
     * Recognised forms:
     *  - `matrix.to/#/!opaqueId:server.tld` — canonical room link
     *    Element generates for sharing (0.9 confidence — host + path
     *    pattern is unambiguous Matrix).
     *  - `matrix.to/#/#alias:server.tld` — room-alias link (0.7
     *    confidence — needs an alias → room-id resolve step at
     *    fetch time, which v1 doesn't perform; the resolver falls
     *    back to a low-confidence claim so the user can still pick
     *    Matrix as the route).
     *  - `https://<server>/_matrix/client/v3/rooms/!id:s/messages`
     *    — raw API URL surfaced by curl / docs / debugging (0.9
     *    confidence — distinctive `/_matrix/client/` path).
     */
    override fun matchUrl(url: String): RouteMatch? {
        val trimmed = url.trim()

        // matrix.to canonical room id share link
        MATRIX_TO_ROOM_ID_PATTERN.matchEntire(trimmed)?.let { m ->
            val roomId = m.groupValues[1]
            return RouteMatch(
                sourceId = SourceIds.MATRIX,
                fictionId = matrixFictionId(roomId),
                confidence = 0.9f,
                label = "Matrix room",
            )
        }

        // matrix.to room-alias share link — lower confidence because
        // the alias needs a server-side resolve to a `!roomid:server`
        // before we can fetch. v1 still claims the URL so the user
        // can route via Matrix; an aliased fetch will fast-fail until
        // an alias-resolve endpoint is added (follow-up).
        MATRIX_TO_ROOM_ALIAS_PATTERN.matchEntire(trimmed)?.let { m ->
            val alias = m.groupValues[1]
            return RouteMatch(
                sourceId = SourceIds.MATRIX,
                fictionId = matrixFictionId(alias),
                confidence = 0.7f,
                label = "Matrix room alias",
            )
        }

        // Raw client-server API URL — distinctive `/_matrix/client/`
        // path makes this unambiguous Matrix regardless of which
        // homeserver hosts it.
        MATRIX_CS_API_PATTERN.matchEntire(trimmed)?.let { m ->
            val roomId = m.groupValues[1]
            return RouteMatch(
                sourceId = SourceIds.MATRIX,
                fictionId = matrixFictionId(roomId),
                confidence = 0.9f,
                label = "Matrix room",
            )
        }

        return null
    }

    // ─── browse ────────────────────────────────────────────────────────

    /**
     * Front-page = "rooms the user has joined on the configured
     * homeserver". One page, no pagination — the
     * `/joined_rooms` endpoint returns the full list in one
     * response and joined-room counts are typically modest (a few
     * hundred at most for a heavy Matrix user).
     *
     * For each joined room, we fetch the room name + topic state
     * events to render a readable `FictionSummary`. Failures on
     * individual rooms (member without permission to read state,
     * deleted-since-join) silently fall through to a placeholder
     * title so one bad room doesn't break the whole list.
     */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (!state.isConfigured) {
            return FictionResult.AuthRequired(
                "Matrix not configured. Add your homeserver URL and access " +
                    "token in Settings → Library & Sync → Matrix.",
            )
        }
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val joined = when (val r = api.listJoinedRooms()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val author = state.resolvedUserId.ifBlank { "Matrix" }
        val summaries = joined.joinedRooms.map { roomId ->
            val name = (api.getRoomName(roomId) as? FictionResult.Success)?.value?.name
                ?.takeIf { it.isNotBlank() }
                ?: roomId
            val topic = (api.getRoomTopic(roomId) as? FictionResult.Success)?.value?.topic
                ?.takeIf { it.isNotBlank() }
            FictionSummary(
                id = matrixFictionId(roomId),
                sourceId = SourceIds.MATRIX,
                title = name,
                author = author,
                description = topic,
                status = FictionStatus.ONGOING,
            )
        }.sortedBy { it.title.lowercase() }
        return FictionResult.Success(ListPage(items = summaries, page = 1, hasNext = false))
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        // No separate "recently active" ordering for v1 — the
        // joined-rooms list doesn't carry per-room recency, and
        // sorting by it would require N extra round-trips to fetch
        // the last message timestamp per room. Collapse to popular()
        // until that's worth the latency.
        popular(page)

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // Matrix rooms have no built-in genre / category faceting.
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    /**
     * Matrix homeserver-side full-text search via
     * `/_matrix/client/v3/search`. Not every homeserver implements
     * it (the spec marks it optional; matrix.org has it on, some
     * smaller self-hosted Synapse instances disable it for
     * performance reasons). When the homeserver responds with 404
     * or `M_NOT_IMPLEMENTED`, the source surfaces an empty result
     * page rather than a hard failure so the Browse search tab
     * shows the standard "no results" empty state.
     *
     * v1 implementation note: storyvox doesn't post the structured
     * search-categories body the spec requires; instead it
     * fast-fails with an empty result page and the UI's empty
     * state explains "Search not available on this homeserver".
     * A v2 follow-up can POST the proper `{search_categories:
     * {room_events: {search_term: "..."}}}` body — out of scope
     * for the architectural-twin v1 acceptance.
     */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val state = config.current()
        if (!state.isConfigured) {
            return FictionResult.AuthRequired(
                "Matrix not configured.",
            )
        }
        // v1 fast-falls back to empty — homeserver search posts a
        // structured body and a v2 PR can wire that up. Browse
        // surfaces this as "Search not available on this
        // homeserver" per the issue spec's empty-state acceptance.
        return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
    }

    // ─── detail ────────────────────────────────────────────────────────

    /**
     * One room's recent message history → chapter list. Fetches up
     * to 100 events backwards from head, filters to
     * `m.room.message`-typed events, reverses to chronological,
     * coalesces same-sender runs within the configured window.
     *
     * The chapter title is `"{sender display name} — {snippet}"`
     * where snippet is the first ~60 chars of the head message's
     * `body`. Media events (`m.image` / `m.file` / etc.) fall back
     * to `"{sender} — Attachment: {filename}"`.
     */
    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val state = config.current()
        if (!state.isConfigured) {
            return FictionResult.AuthRequired(
                "Matrix not configured.",
            )
        }
        val roomId = fictionId.toRoomId()
            ?: return FictionResult.NotFound("Matrix fiction id not recognized: $fictionId")

        val nameResult = api.getRoomName(roomId)
        val topicResult = api.getRoomTopic(roomId)
        val name = (nameResult as? FictionResult.Success)?.value?.name?.takeIf { it.isNotBlank() }
            ?: roomId
        val topic = (topicResult as? FictionResult.Success)?.value?.topic?.takeIf { it.isNotBlank() }

        val messages = when (val r = api.listMessages(roomId, from = null)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        // Backwards-paginated chunk is newest-first; reverse for
        // chronological reading. Filter to user-visible message
        // events; everything else (member joins, redactions,
        // reactions, receipts) doesn't belong in an audiobook.
        val chronological = messages.chunk
            .asReversed()
            .filter { it.type == "m.room.message" }

        val groups = coalesceMatrixEvents(chronological, state.coalesceMinutes)

        // Resolve sender display names once per group head — the
        // chapter title uses the head sender's name. We pull names
        // for the heads we actually render, not every event in the
        // chunk, to keep the round-trip count bounded.
        val resolvedNames = mutableMapOf<String, String>()
        for (group in groups) {
            val sender = group.headEvent.sender
            if (sender.isNotBlank() && sender !in resolvedNames) {
                resolvedNames[sender] = resolveDisplayName(sender)
            }
        }

        val chapters = groups.mapIndexed { idx, group ->
            ChapterInfo(
                id = chapterIdFor(fictionId, group.headEvent.eventId),
                sourceChapterId = "event-${group.headEvent.eventId}",
                index = idx,
                title = group.title(resolvedNames[group.headEvent.sender].orEmpty()),
                publishedAt = group.headTimestampMillis,
            )
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.MATRIX,
            title = name,
            author = state.resolvedUserId.ifBlank { "Matrix" },
            description = topic,
            status = FictionStatus.ONGOING,
            chapterCount = chapters.size,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    /**
     * One chapter body. Re-fetches the room's recent history,
     * locates the coalesced group whose head event matches the
     * chapter's event id, renders the combined body.
     *
     * Stateless w.r.t. the caller — caching is the repository
     * layer's job; this source re-fetches.
     */
    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val state = config.current()
        if (!state.isConfigured) {
            return FictionResult.AuthRequired(
                "Matrix not configured.",
            )
        }
        val roomId = fictionId.toRoomId()
            ?: return FictionResult.NotFound("Matrix fiction id not recognized: $fictionId")
        val headEventId = chapterId.substringAfterLast("::event-", "")
            .takeIf { it.isNotBlank() }
            ?: return FictionResult.NotFound("Matrix chapter id not recognized: $chapterId")

        return when (val r = api.listMessages(roomId, from = null)) {
            is FictionResult.Success -> {
                val chronological = r.value.chunk
                    .asReversed()
                    .filter { it.type == "m.room.message" }
                val groups = coalesceMatrixEvents(chronological, state.coalesceMinutes)
                val group = groups.firstOrNull { it.headEvent.eventId == headEventId }
                    ?: return FictionResult.NotFound(
                        "Matrix chapter (event $headEventId) not in recent history",
                    )
                val senderName = resolveDisplayName(group.headEvent.sender)
                val index = groups.indexOf(group)
                val info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = "event-${group.headEvent.eventId}",
                    index = index,
                    title = group.title(senderName),
                    publishedAt = group.headTimestampMillis,
                )
                FictionResult.Success(
                    ChapterContent(
                        info = info,
                        htmlBody = group.toHtml { resolveDisplayNameCached(it) },
                        plainBody = group.toPlainText { resolveDisplayNameCached(it) },
                    ),
                )
            }
            is FictionResult.Failure -> r
        }
    }

    // ─── follow ────────────────────────────────────────────────────────

    /**
     * Matrix rooms have no "follow" concept distinct from "be a
     * joined member of the room". [supportsFollow] is false so the
     * Follow button stays hidden in FictionDetail for Matrix
     * fictions.
     */
    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── helpers ───────────────────────────────────────────────────────

    /**
     * Resolve a sender id → display name, hitting the cache first.
     * Fetches the homeserver's `/profile/{userId}/displayname` on a
     * cache miss; an empty / null displayname caches the empty
     * string so we don't re-fetch.
     */
    private suspend fun resolveDisplayName(userId: String): String {
        displayNameCache[userId]?.let { return it }
        val resolved = when (val r = api.getDisplayName(userId)) {
            is FictionResult.Success -> r.value.displayname?.takeIf { it.isNotBlank() } ?: ""
            is FictionResult.Failure -> ""
        }
        displayNameCache[userId] = resolved
        return resolved
    }

    /**
     * Synchronous cache lookup for the HTML/plaintext renderers,
     * which can't suspend (they run inside [MatrixEventGroup.toHtml]).
     * Returns the cached display name if known; empty string if not
     * yet resolved. The async [resolveDisplayName] is called ahead
     * of rendering by the source's `chapter` path so the cache is
     * primed by the time the renderer asks.
     */
    private fun resolveDisplayNameCached(userId: String): String =
        displayNameCache[userId].orEmpty()

    /** Internal test hook — clear the per-process display-name
     *  cache. Production code doesn't call this; tests use it
     *  to start each scenario from a clean state. */
    internal fun clearCaches() {
        displayNameCache.clear()
    }
}

// ─── ids + URLs ───────────────────────────────────────────────────────

/**
 * matrix.to canonical room-id share link, e.g.
 * `https://matrix.to/#/!abc123:matrix.org`. The `!` distinguishes
 * an opaque room id from a `#alias:server` form. We URL-decode the
 * captured group at fiction-id construction time so `%21` in shared
 * links round-trips back to `!`.
 */
internal val MATRIX_TO_ROOM_ID_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?matrix\.to/#/(![^/?#]+)(?:/.*)?(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * matrix.to room-alias share link, e.g.
 * `https://matrix.to/#/#storyvox:matrix.org`. Aliases need a
 * server-side resolve step before they can be fetched; v1 surfaces
 * them as low-confidence claims so the user can still route via
 * Matrix while the resolve step is a follow-up.
 */
internal val MATRIX_TO_ROOM_ALIAS_PATTERN: Regex = Regex(
    """^https?://(?:www\.)?matrix\.to/#/(#[^/?#]+)(?:/.*)?(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Raw Client-Server API URL pattern, e.g.
 * `https://matrix.org/_matrix/client/v3/rooms/!abc:matrix.org/messages`.
 * Distinctive `/_matrix/client/` path makes this unambiguous Matrix.
 */
internal val MATRIX_CS_API_PATTERN: Regex = Regex(
    """^https?://[^/]+/_matrix/client/v\d+/rooms/(![^/]+)(?:/messages)?(?:\?.*)?$""",
    RegexOption.IGNORE_CASE,
)

/**
 * Stable Matrix fiction id from a room id (or room alias). The
 * room id is URL-encoded so `!` and `:` don't collide with the
 * `matrix:` prefix or the chapter id's `::` separator when the
 * combined fiction-id string passes through DataStore / Room.
 */
internal fun matrixFictionId(roomId: String): String =
    "matrix:${URLEncoder.encode(roomId, "UTF-8")}"

/** Compose a chapter id from a fiction id + Matrix event id. */
internal fun chapterIdFor(fictionId: String, eventId: String): String =
    "$fictionId::event-$eventId"

/**
 * Decode the room id from a Matrix fiction id, or null when the id
 * doesn't carry the `matrix:` prefix. URL-decoding round-trips the
 * `!` / `:` characters that the fiction-id construction encoded.
 */
internal fun String.toRoomId(): String? {
    if (!startsWith("matrix:")) return null
    val encoded = removePrefix("matrix:").substringBefore("::")
    if (encoded.isBlank()) return null
    return URLDecoder.decode(encoded, "UTF-8")
}

// ─── coalescing + rendering ───────────────────────────────────────────

/**
 * One coalesced chapter — a contiguous run of same-sender events
 * within the coalesce window. The head event anchors the chapter
 * id; siblings render into the body.
 */
internal data class MatrixEventGroup(
    /** Head event — anchors the chapter id, drives the title. */
    val headEvent: MatrixEvent,
    /** All events in the group, in chronological order. The head
     *  is `events.first()`. */
    val events: List<MatrixEvent>,
    /** Head event's origin_server_ts (unix millis). */
    val headTimestampMillis: Long,
)

/**
 * Build a chapter title from a coalesced group. Format:
 * `"{sender} — {snippet}"` where sender is the resolved display
 * name (falls back to the bare `@handle:server` Matrix id if not
 * resolved) and snippet is the first ~60 chars of the head event's
 * body. Media events render as
 * `"{sender} — Attachment: {filename}"`.
 *
 * [resolvedSender] is the pre-resolved display name; an empty
 * string here triggers the fallback to the bare Matrix id.
 */
internal fun MatrixEventGroup.title(resolvedSender: String): String {
    val sender = resolvedSender.ifBlank { headEvent.sender.ifBlank { "Unknown" } }
    val snippet = headEvent.contentPreview()
    return "$sender — $snippet"
}

/** Render the group as HTML — one `<p>` per event, media events
 *  surface as `<p>Attachment: …</p>` lines so the reader view + TTS
 *  both mention them. [senderResolver] is a synchronous cache lookup
 *  for display names — the renderer can't suspend. */
internal fun MatrixEventGroup.toHtml(senderResolver: (String) -> String): String {
    return events.joinToString("\n") { e ->
        val parts = buildList {
            val body = e.body
            if (body.isNotBlank()) {
                when (e.msgtype) {
                    "m.text", "m.notice", "m.emote", "" -> add("<p>${htmlEscape(body)}</p>")
                    "m.image", "m.file", "m.video", "m.audio" ->
                        add("<p>Attachment: ${htmlEscape(body)}</p>")
                    else -> add("<p>${htmlEscape(body)}</p>")
                }
            }
        }
        parts.joinToString("\n")
    }
}

/** Plain-text projection for TTS. Strips markup; media events
 *  become natural-language "Attachment: filename" lines so they
 *  narrate cleanly. */
internal fun MatrixEventGroup.toPlainText(senderResolver: (String) -> String): String {
    val lines = events.flatMap { e ->
        val body = e.body
        if (body.isBlank()) {
            emptyList()
        } else when (e.msgtype) {
            "m.image", "m.file", "m.video", "m.audio" -> listOf("Attachment: $body")
            else -> listOf(body)
        }
    }
    return lines.joinToString("\n\n").trim()
}

/**
 * First ~60 chars of the event body, with newlines collapsed to
 * spaces. Falls back to `"Attachment: filename"` for media-only
 * events, then to `"(empty event)"`.
 */
internal fun MatrixEvent.contentPreview(): String {
    val body = body
    if (body.isNotBlank()) {
        val prefix = when (msgtype) {
            "m.image", "m.file", "m.video", "m.audio" -> "Attachment: "
            else -> ""
        }
        val flat = body.replace('\n', ' ').replace('\r', ' ').trim()
        val withPrefix = "$prefix$flat"
        return if (withPrefix.length <= 60) withPrefix else withPrefix.take(57).trimEnd() + "…"
    }
    return "(empty event)"
}

/**
 * Coalesce a chronological list of Matrix events into groups, where
 * each group is a run of consecutive same-sender events whose
 * adjacent timestamps are within [coalesceMinutes] of each other.
 *
 * Edge cases:
 *  - Empty input → empty output.
 *  - `coalesceMinutes <= 0` → every event is its own group (defends
 *    against a drift in the persisted slider value below the UI's
 *    1-minute minimum).
 *  - Events with zero / missing timestamps fall back to "always
 *    boundary" — they don't merge with neighbours. Safer than
 *    merging on unknown time, which would silently collapse runs.
 */
internal fun coalesceMatrixEvents(
    chronological: List<MatrixEvent>,
    coalesceMinutes: Int,
): List<MatrixEventGroup> {
    if (chronological.isEmpty()) return emptyList()
    val windowMs = coalesceMinutes.coerceAtLeast(0).toLong() * 60_000L
    val groups = mutableListOf<MatrixEventGroup>()
    var currentEvents = mutableListOf<MatrixEvent>()
    var currentSender: String? = null
    var currentLastTs: Long = 0L

    fun flush() {
        if (currentEvents.isEmpty()) return
        val head = currentEvents.first()
        groups.add(
            MatrixEventGroup(
                headEvent = head,
                events = currentEvents.toList(),
                headTimestampMillis = head.originServerTs,
            ),
        )
        currentEvents = mutableListOf()
        currentSender = null
        currentLastTs = 0L
    }

    for (e in chronological) {
        val ts = e.originServerTs
        val sameSender = currentSender == e.sender
        val withinWindow = windowMs > 0 && ts > 0 && currentLastTs > 0 &&
            (ts - currentLastTs) <= windowMs
        if (sameSender && withinWindow) {
            currentEvents.add(e)
            currentLastTs = ts
        } else {
            flush()
            currentEvents.add(e)
            currentSender = e.sender
            currentLastTs = ts
        }
    }
    flush()
    return groups
}

/** Minimal HTML escape — angle brackets, ampersand, double-quote. */
internal fun htmlEscape(s: String): String = s
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
