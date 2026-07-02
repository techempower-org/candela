package `in`.jphe.storyvox.source.mempalace

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.mempalace.model.PalaceDrawer
import `in`.jphe.storyvox.source.mempalace.model.PalaceGraph
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonApi
import `in`.jphe.storyvox.source.mempalace.net.PalaceDaemonResult
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemPalace [FictionSource]. Read-only in v1 (#79).
 *
 * Mapping: a palace **room** is a fiction; each **drawer** in that
 * room is a chapter. See `docs/superpowers/specs/2026-05-08-mempalace-
 * integration-design.md` for the full design and the rationale for
 * room-as-fiction over wing-as-fiction or drawer-as-fiction.
 *
 *  - **Browse** Wings tab: top-N rooms by drawer count, derived from
 *    `GET /graph` and re-keyed by `room`. Cached client-side per call.
 *  - **Browse** Recent tab: the most recently filed drawer's room,
 *    deduped to one entry per `wing/room`. Pulled from `GET /list?limit=N`
 *    (no wing filter — global recent stream).
 *  - **By Wing** filter: surfaces `byGenre(wing)` — same shape as Wings
 *    but scoped.
 *  - **Search**: returns empty in v1; tab is hidden in Browse.
 *  - **Detail**: paginates `GET /list?wing=&room=&limit=200&offset=N`
 *    until the room's drawers are enumerated, mapping each drawer to
 *    a [ChapterInfo].
 *  - **Chapter**: `mempalace_get_drawer({drawer_id})` via the daemon's
 *    `/mcp` passthrough; returns the verbatim content as plaintext.
 *  - **Follows / setFollowed**: not supported (palace doesn't have a
 *    follows concept). Returns [FictionResult.AuthRequired].
 *
 * Hilt binding lives in [`in`.jphe.storyvox.source.mempalace.di
 * .MemPalaceBindings] — `@IntoMap @StringKey(SourceIds.MEMPALACE)`.
 */
@SourcePlugin(
    id = SourceIds.MEMPALACE,
    displayName = "Memory Palace",
    // #436 — fresh-install discoverability: chip visible by default;
    // the daemon host config still gates real listings until the user
    // points the app at a reachable host, but hiding the chip entirely
    // means a fresh install can't discover the feature exists.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = true,
    description = "Your local mempalace daemon · LAN-only · wings + rooms become fictions",
    sourceUrl = "http://mempalace.realm.watch",
)
@Singleton
internal class MemPalaceSource @Inject constructor(
    private val api: PalaceDaemonApi,
) : FictionSource, `in`.jphe.storyvox.data.source.UrlMatcher {

    override val id: String = SourceIds.MEMPALACE
    override val displayName: String = "Memory Palace"

    /** Issue #472 — `mempalace.realm.watch/wing/<wing>/<room>` URLs.
     *  Restricted to the canonical MP daemon host so a paste from
     *  any non-MP URL doesn't get claimed. */
    override fun matchUrl(url: String): `in`.jphe.storyvox.data.source.RouteMatch? {
        val m = Regex(
            """^https?://mempalace(?:\.realm\.watch|\.jphe\.in|\.local)/(?:wing|room)/([\w./-]+)(?:[?#].*)?$""",
            RegexOption.IGNORE_CASE,
        ).matchEntire(url.trim()) ?: return null
        return `in`.jphe.storyvox.data.source.RouteMatch(
            sourceId = SourceIds.MEMPALACE,
            fictionId = "${SourceIds.MEMPALACE}:${m.groupValues[1]}",
            confidence = 0.95f,
            label = "Memory Palace",
        )
    }

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) return FictionResult.Success(emptyPage(page))
        return when (val r = api.graph()) {
            is PalaceDaemonResult.Success -> {
                val items = topRoomsByDrawerCount(r.value, limit = WINGS_TAB_LIMIT)
                FictionResult.Success(ListPage(items, page = 1, hasNext = false))
            }
            else -> r.toFictionFailure(operation = "popular")
        }
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) return FictionResult.Success(emptyPage(page))
        return when (val r = api.list(limit = RECENT_TAB_FETCH, offset = 0)) {
            is PalaceDaemonResult.Success -> {
                // Drawer summaries don't carry a timestamp so we can't sort
                // truly by filed_at without fetching each drawer. Trade-off:
                // the daemon returns drawers in metadata-table order which
                // approximates insertion order. We dedupe by (wing, room)
                // and take the first occurrence — the most-recently-touched
                // room appears first.
                val seen = LinkedHashMap<Pair<String, String>, FictionSummary>()
                for (d in r.value.drawers) {
                    val key = d.wing to d.room
                    if (seen.containsKey(key)) continue
                    seen[key] = roomToSummary(d.wing, d.room, drawerCount = null)
                }
                FictionResult.Success(
                    ListPage(seen.values.toList(), page = 1, hasNext = false),
                )
            }
            else -> r.toFictionFailure(operation = "latestUpdates")
        }
    }

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) return FictionResult.Success(emptyPage(page))
        val needle = genre.trim()
        if (needle.isBlank()) return popular(page)
        return when (val r = api.graph()) {
            is PalaceDaemonResult.Success -> {
                val rooms = r.value.rooms
                    .firstOrNull { it.wing.equals(needle, ignoreCase = true) }
                    ?.rooms.orEmpty()
                val items = rooms.entries
                    .sortedByDescending { it.value }
                    .take(WINGS_TAB_LIMIT)
                    .map { (room, count) -> roomToSummary(needle, room, count) }
                FictionResult.Success(ListPage(items, page = 1, hasNext = false))
            }
            else -> r.toFictionFailure(operation = "byGenre")
        }
    }

    override suspend fun genres(): FictionResult<List<String>> =
        when (val r = api.graph()) {
            is PalaceDaemonResult.Success ->
                FictionResult.Success(r.value.wings.keys.sorted())
            else -> r.toFictionFailureList(operation = "genres")
        }

    override suspend fun search(
        @Suppress("UNUSED_PARAMETER") query: SearchQuery,
    ): FictionResult<ListPage<FictionSummary>> {
        // v1: search tab is hidden in BrowseScreen so this should never
        // get called, but the FictionSource interface requires a body
        // and returning empty is gentler than throwing if some future
        // caller wires it. P1 surfaces the daemon's /search endpoint.
        return FictionResult.Success(ListPage(emptyList(), page = 1, hasNext = false))
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val (wing, room) = MemPalaceIds.parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a palace fiction id: $fictionId")
        // Page through /list collecting all drawers in this room. Cap at
        // CHAPTER_LIST_HARD_CAP to keep the eager load bounded; rooms
        // larger than that are rare on JP's palace and the cap ships a
        // P1 follow-up to lazy-load.
        val drawers = mutableListOf<ChapterInfo>()
        var offset = 0
        while (drawers.size < CHAPTER_LIST_HARD_CAP) {
            when (val r = api.list(wing = wing, room = room, limit = LIST_PAGE, offset = offset)) {
                is PalaceDaemonResult.Success -> {
                    val page = r.value.drawers
                    if (page.isEmpty()) break
                    page.forEachIndexed { i, summary ->
                        drawers += ChapterInfo(
                            id = MemPalaceIds.chapterId(wing, room, summary.drawerId),
                            sourceChapterId = summary.drawerId,
                            index = drawers.size + i,
                            title = drawerTitle(summary.contentPreview, summary.drawerId),
                        )
                    }
                    if (page.size < LIST_PAGE) break
                    offset += page.size
                }
                else -> return r.toFictionFailure(operation = "fictionDetail")
            }
        }
        if (drawers.isEmpty()) {
            return FictionResult.NotFound(
                message = "No drawers in $wing/$room (room may have been renamed).",
            )
        }
        return FictionResult.Success(
            FictionDetail(
                summary = roomToSummary(wing, room, drawers.size),
                chapters = drawers,
                genres = listOf(wing),
                wordCount = null,
                views = null,
                followers = null,
                lastUpdatedAt = null,
                authorId = null,
            ),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val (wing, room, drawerId) = MemPalaceIds.parseChapterId(chapterId)
            ?: return FictionResult.NotFound(message = "Not a palace chapter id: $chapterId")
        // fictionId is informational here — we only need drawerId — but
        // sanity-check that the chapter belongs to the claimed fiction.
        val parsedFiction = MemPalaceIds.parseFictionId(fictionId)
        if (parsedFiction != null && (parsedFiction.first != wing || parsedFiction.second != room)) {
            return FictionResult.NotFound(
                message = "Chapter $chapterId does not belong to fiction $fictionId.",
            )
        }
        return when (val r = api.getDrawer(drawerId)) {
            is PalaceDaemonResult.Success -> FictionResult.Success(
                drawerToChapterContent(chapterId, r.value),
            )
            else -> r.toFictionFailure(operation = "chapter")
        }
    }

    override suspend fun followsList(
        @Suppress("UNUSED_PARAMETER") page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        FictionResult.AuthRequired(message = "Palace doesn't have a follows concept.")

    override suspend fun setFollowed(
        @Suppress("UNUSED_PARAMETER") fictionId: String,
        @Suppress("UNUSED_PARAMETER") followed: Boolean,
    ): FictionResult<Unit> =
        FictionResult.AuthRequired(message = "Palace doesn't have a follows concept.")

    /**
     * Cheap-poll revision token: the most-recently-listed drawer id
     * in this room. The poll worker compares against the previously-
     * stored token and skips the heavier `fictionDetail` round-trip
     * when they match. A single `/list?limit=1` is the cheapest
     * signal we have.
     */
    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        val (wing, room) = MemPalaceIds.parseFictionId(fictionId)
            ?: return FictionResult.NotFound(message = "Not a palace fiction id: $fictionId")
        return when (val r = api.list(wing = wing, room = room, limit = 1, offset = 0)) {
            is PalaceDaemonResult.Success ->
                FictionResult.Success(r.value.drawers.firstOrNull()?.drawerId)
            else -> r.toFictionFailure<String?>(operation = "latestRevisionToken")
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private fun topRoomsByDrawerCount(
        graph: PalaceGraph,
        limit: Int,
    ): List<FictionSummary> = graph.rooms
        .flatMap { wingRooms ->
            wingRooms.rooms.entries.map { (room, count) -> Triple(wingRooms.wing, room, count) }
        }
        .sortedByDescending { it.third }
        .take(limit)
        .map { (wing, room, count) -> roomToSummary(wing, room, count) }

    private fun roomToSummary(
        wing: String,
        room: String,
        drawerCount: Int?,
    ): FictionSummary = FictionSummary(
        id = MemPalaceIds.fictionId(wing, room),
        sourceId = SourceIds.MEMPALACE,
        title = MemPalaceIds.prettify(room),
        author = "MemPalace · ${MemPalaceIds.prettify(wing)}",
        coverUrl = null,
        description = drawerCount?.let { "$it entries from your palace." }
            ?: "Entries from your palace.",
        tags = listOf(wing),
        status = FictionStatus.ONGOING,
        chapterCount = drawerCount,
        rating = null,
    )

    private fun drawerToChapterContent(
        chapterId: String,
        drawer: PalaceDrawer,
    ): ChapterContent {
        val title = drawerTitle(drawer.content, drawer.drawerId, drawer.metadata.sourceFile, drawer.metadata.chunkIndex)
        // Drawer content is verbatim text — could be markdown, plaintext,
        // code, or mixed. We hand the same body to both reader (htmlBody)
        // and TTS (plainBody). The reader's existing pipeline will do
        // basic safe-HTML escape on display; TTS chunker handles the
        // plaintext directly.
        val plain = drawer.content.trim()
        val html = "<pre>" + escapeHtml(plain) + "</pre>"
        return ChapterContent(
            info = ChapterInfo(
                id = chapterId,
                sourceChapterId = drawer.drawerId,
                index = drawer.metadata.chunkIndex,
                title = title,
            ),
            htmlBody = html,
            plainBody = plain,
            notesAuthor = null,
            notesAuthorPosition = null,
        )
    }

    /** Derive a human chapter title from preview content + metadata. */
    internal fun drawerTitle(
        content: String?,
        drawerId: String,
        sourceFile: String? = null,
        chunkIndex: Int = 0,
    ): String {
        val base = sourceFile?.takeIf { it.isNotBlank() }
            ?.substringAfterLast('/')
            ?.substringBeforeLast('.')
            // Lowercase first so SHOUTING_FILES like OVERVIEW.txt and
            // BIG.md prettify to "Overview" / "Big" rather than staying
            // upper-case after [MemPalaceIds.prettify] only capitalises
            // the first letter.
            ?.lowercase()
            ?.let { MemPalaceIds.prettify(it) }
            ?: firstHeadingOrLine(content)
            ?: drawerIdShort(drawerId)
        return if (chunkIndex > 0) "$base (part ${chunkIndex + 1})" else base
    }

    private fun firstHeadingOrLine(content: String?): String? {
        if (content.isNullOrBlank()) return null
        val firstLine = content.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() } ?: return null
        // Strip markdown heading markers if present.
        val stripped = firstLine.trimStart('#').trim()
        return stripped.take(60).ifBlank { null }
    }

    private fun drawerIdShort(drawerId: String): String =
        // drawerId looks like `drawer_<wing>_<room>_<hex>`; show the hex
        // tail so collisions in the same room are visually distinct.
        drawerId.substringAfterLast('_').take(8)

    private fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun emptyPage(page: Int): ListPage<FictionSummary> =
        ListPage(items = emptyList(), page = page, hasNext = false)

    /** Map a daemon failure variant to the cross-source FictionResult. */
    private fun <T> PalaceDaemonResult<*>.toFictionFailure(operation: String): FictionResult<T> = when (this) {
        is PalaceDaemonResult.NotFound -> FictionResult.NotFound(message = message)
        is PalaceDaemonResult.Unauthorized -> FictionResult.AuthRequired(message = "Palace API key rejected.")
        is PalaceDaemonResult.Degraded -> FictionResult.NetworkError(
            message = "Palace is rebuilding — try again shortly.",
        )
        is PalaceDaemonResult.NotReachable -> {
            // Palace API surfaces "host not configured" (empty Settings →
            // Memory Palace host textfield) as a NotReachable with that
            // exact IOException message — see PalaceDaemonApi.kt's
            // `if (!cfg.isConfigured)` early returns at lines 95 + 144.
            // Without this branch, an unconfigured user sees "Reconnect
            // on home network" — wrong cause, no actionable path. Issue
            // #164. The proper fix for this is a typed-result error
            // kind (mirroring PR #154's RecapUiState.ErrorKind shape)
            // with a dedicated "Open Settings" CTA in the empty state;
            // tracked as a follow-up. The copy fix here closes the
            // immediate user-impact gap by directing them to the right
            // place in Settings instead of telling them to reconnect
            // a Wi-Fi connection that's already fine.
            val isUnconfigured = (cause as? IOException)?.message ==
                "Palace host not configured"
            if (isUnconfigured) {
                FictionResult.NetworkError(
                    message = "Set up your Memory Palace host in Settings → Memory Palace to browse your private fictions.",
                    cause = cause,
                )
            } else {
                FictionResult.NetworkError(
                    message = "Could not reach the palace daemon. Reconnect on home network or check the host address.",
                    cause = cause,
                )
            }
        }
        is PalaceDaemonResult.HostRejected -> FictionResult.NetworkError(
            message = "Palace host '${host}' is not on the home network.",
        )
        is PalaceDaemonResult.HttpError -> FictionResult.NetworkError(
            message = "Palace daemon returned $code during $operation: $message",
        )
        is PalaceDaemonResult.ParseError -> FictionResult.NetworkError(
            message = "Malformed response from palace daemon during $operation",
            cause = cause,
        )
        is PalaceDaemonResult.Success<*> -> error(
            "toFictionFailure called on Success — caller should branch on Success first.",
        )
    }

    /** Specialisation for the [genres] return type. */
    private fun PalaceDaemonResult<*>.toFictionFailureList(operation: String): FictionResult<List<String>> =
        toFictionFailure(operation)

    private companion object {
        /** Top-N rooms returned by the Wings tab. */
        const val WINGS_TAB_LIMIT = 50
        /** How many drawers to ask for when populating the Recent tab. */
        const val RECENT_TAB_FETCH = 100
        /** Page size for the chapter listing fan-out. */
        const val LIST_PAGE = 100
        /**
         * Hard cap on drawers loaded into a single fiction's chapter
         * list. Rooms above this cap exist on JP's palace (`bestiary/
         * technical` is 54K); the chapter list shows the first
         * CHAPTER_LIST_HARD_CAP and reading still works for those
         * specific drawers. P1 follow-up to lazy-stream the listing.
         */
        const val CHAPTER_LIST_HARD_CAP = 2000
    }
}
