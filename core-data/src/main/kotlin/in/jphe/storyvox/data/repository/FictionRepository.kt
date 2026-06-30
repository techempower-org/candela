package `in`.jphe.storyvox.data.repository

import `in`.jphe.storyvox.data.db.StoryvoxDatabase
import `in`.jphe.storyvox.data.db.dao.ChapterDao
import `in`.jphe.storyvox.data.db.dao.FictionDao
import `in`.jphe.storyvox.data.db.entity.Chapter
import `in`.jphe.storyvox.data.db.entity.ChapterDownloadState
import `in`.jphe.storyvox.data.db.entity.DownloadMode
import `in`.jphe.storyvox.data.db.entity.Fiction
import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.RouteMatch
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.UrlResolver
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The aggregate read/write surface over fiction metadata. UI never touches
 * Room directly; everything goes through this interface.
 */
interface FictionRepository {

    fun observeLibrary(): Flow<List<FictionSummary>>
    fun observeFollowsRemote(): Flow<List<FictionSummary>>
    fun observeFiction(id: String): Flow<FictionDetail?>

    /**
     * Targeted boolean stream: true when the fiction row exists in the
     * user's library. Unlike [observeLibrary] (which emits the entire
     * list on any library change), this flow is scoped to a single row
     * and only re-emits when *this* fiction's `inLibrary` column flips.
     * Used by FictionDetailViewModel to avoid the O(n) full-list scan.
     */
    fun observeIsInLibrary(id: String): Flow<Boolean>

    /**
     * Browse the popular fictions on [sourceId]. Defaults to
     * [SourceIds.ROYAL_ROAD] for backwards-compat with existing
     * callers; UI surfaces that route to other sources (e.g.
     * Browse → GitHub) pass the chosen sourceId explicitly.
     */
    suspend fun browsePopular(
        page: Int,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun browseLatest(
        page: Int,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun browseByGenre(
        genre: String,
        page: Int,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun search(
        query: SearchQuery,
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<ListPage<FictionSummary>>

    /**
     * Cache an externally-fetched browse listing the same way
     * [browsePopular] / [search] do — `upsertAllPreservingUserState` so
     * a subsequent `refreshDetail(id)` finds a row with the right
     * `sourceId` and routes to the correct source.
     *
     * Used by source-specific listing endpoints that don't fit
     * [SearchQuery] (e.g. GitHub `/user/repos` for #200) so their
     * results materialize as DB-backed fictions when the user taps a
     * card. [result] is returned untouched on either success or
     * failure; callers use this as a transparent passthrough.
     */
    suspend fun cacheBrowseListing(
        result: FictionResult<ListPage<FictionSummary>>,
    ): FictionResult<ListPage<FictionSummary>>

    suspend fun genres(
        sourceId: String = SourceIds.ROYAL_ROAD,
    ): FictionResult<List<String>>

    /**
     * Force a detail-page refresh, upserting the cached row.
     *
     * Issue #1314 — stale-while-revalidate TTL guard. With [force] = false
     * (the default, used by the auto first-subscription refresh in
     * `fictionById`) the network fetch is skipped when the cached row was
     * hydrated within the TTL window — a rapid re-open of a just-viewed
     * fiction reads straight from Room instead of re-hitting the source.
     * [force] = true bypasses the TTL for deliberate refreshes: the manual
     * Retry button (`retryDetail`), the new-chapter poll worker, and the
     * metadata back-fill worker all must fetch regardless of cache age.
     * Placeholder rows (`metadataFetchedAt == 0`) are always stale, so the
     * back-fill path fetches even without [force].
     */
    suspend fun refreshDetail(id: String, force: Boolean = false): FictionResult<Unit>

    /**
     * Re-fetch the user's source-side follows list and reconcile against the
     * local DB: rows that appear remotely get `followedRemotely = true`, rows
     * that previously had it but are absent get cleared. Requires an
     * authenticated session; returns [FictionResult.AuthRequired] if not.
     */
    suspend fun refreshRemoteFollows(): FictionResult<Unit>

    suspend fun addToLibrary(id: String, mode: DownloadMode? = null)
    suspend fun removeFromLibrary(id: String)
    suspend fun setDownloadMode(id: String, mode: DownloadMode?)
    suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?)

    /** Issue #1299 — read the per-fiction narrator voice pin, or null when
     *  unpinned. [EnginePlayer.loadAndPlay] reads this to narrate with the
     *  book's pinned voice, falling back to the global active voice. */
    suspend fun pinnedVoiceId(id: String): String?

    /**
     * Issue #1231 — per-fiction playback speed. [setPlaybackSpeed] pins (or,
     * with `null`, clears) the book's own speed; [observePlaybackSpeed] is the
     * reactive read the playback wiring uses to auto-restore that speed on
     * load and the reader UI uses to reflect whether "this book" is pinned.
     */
    suspend fun setPlaybackSpeed(id: String, speed: Float?)
    fun observePlaybackSpeed(id: String): Flow<Float?>

    suspend fun setFollowedRemote(id: String, followed: Boolean): FictionResult<Unit>

    /**
     * Issue #982 — "Mark all caught up" on the Follows tab. Marks every unread
     * chapter of every followed fiction (`followedRemotely = 1`) as read in a
     * single statement, persisting to the same `chapter.userMarkedRead` column
     * playback and the chat tools write. Returns the number of chapters this
     * transitioned, so the caller can avoid signalling a save when there was
     * nothing unread to catch up on.
     */
    suspend fun markAllCaughtUp(): Int

    /**
     * Resolve a pasted URL (or short form like `owner/repo`) to a fiction,
     * persist a stub row, and refresh its detail. Returns the resolved
     * `fictionId` on success so the UI can navigate to the detail screen.
     *
     * Recognised-but-unsupported sources return
     * [AddByUrlResult.UnsupportedSource] so the UI can surface a
     * "coming soon" message without the user thinking they pasted
     * something invalid.
     *
     * Issue #472 — when multiple backends claim the same URL with high
     * confidence (≥0.5), the repository returns
     * [AddByUrlResult.MultipleMatches] and the UI shows a chooser. The
     * caller then re-invokes with [preferredSourceId] set to the user's
     * picked backend to bypass the resolver and commit the route.
     *
     * @param preferredSourceId Optional override that skips the
     *  resolver's ranking and routes directly to the named source. Used
     *  by the chooser flow described above.
     */
    suspend fun addByUrl(
        url: String,
        preferredSourceId: String? = null,
    ): AddByUrlResult

    /**
     * Issue #472 — preview the routes a paste-anything URL would
     * resolve to without committing to any. Powers the debounced
     * preview row in the Magic-add sheet (icon, source, fiction-title
     * hint). Returns an empty list when the URL doesn't parse at all;
     * a single-element list when one backend claims it; multiple when
     * the chooser modal is warranted.
     */
    fun previewUrl(url: String): List<RouteMatch>
}

/** Outcome of [FictionRepository.addByUrl]. */
sealed class AddByUrlResult {
    /** URL parsed, source supported, detail fetched + persisted. */
    data class Success(val fictionId: String) : AddByUrlResult()

    /** No source's URL pattern matched the input. Should be rare post-#472
     *  because the Readability catch-all claims any HTTP(S) URL at low
     *  confidence — only non-URL inputs (empty string, garbage text)
     *  reach this branch. */
    data object UnrecognizedUrl : AddByUrlResult()

    /** Pattern matched a known source that is wired but not yet implemented. */
    data class UnsupportedSource(val sourceId: String) : AddByUrlResult()

    /** Source-layer failure (network, 404, auth, rate limit, upstream challenge, ...). */
    data class SourceFailure(val failure: FictionResult.Failure) : AddByUrlResult()

    /** Issue #472 — several backends claimed the URL at chooser-eligible
     *  confidence (≥0.5). The UI shows a chooser modal and re-invokes
     *  `addByUrl(url, preferredSourceId = picked)` with the user's
     *  choice. */
    data class MultipleMatches(val candidates: List<RouteMatch>) : AddByUrlResult()
}

@Singleton
class FictionRepositoryImpl @Inject constructor(
    private val sources: Map<String, @JvmSuppressWildcards FictionSource>,
    private val fictionDao: FictionDao,
    private val chapterDao: ChapterDao,
    /** Issue #1437 — the database handle, used only for
     *  [withTransaction] in [upsertDetail]. Nullable so
     *  pure-unit-test callers (no real Room DB) keep compiling. */
    private val db: StoryvoxDatabase? = null,
    /** Issue #472 — magic-link resolver. Walks every registered
     *  plugin's [in.jphe.storyvox.data.source.UrlMatcher] capability
     *  to route a pasted URL to the best backend. Optional in
     *  constructor signature so older tests that build the impl with
     *  a stub `Map<String, FictionSource>` keep compiling — they pass
     *  the empty resolver factory below. */
    private val urlResolver: UrlResolver? = null,
    /** PR-F (#86) — listener that the playback layer's PrerenderTriggers
     *  binds to. Defaults to [FictionLibraryListener.NoOp] so tests
     *  and library-only consumers that don't run the playback layer
     *  don't need to stub. `:app`'s CacheBindingsModule overrides
     *  the binding to PrerenderTriggers in production. */
    private val libraryListener: FictionLibraryListener = FictionLibraryListener.NoOp,
) : FictionRepository {

    /**
     * Resolves a [FictionSource] by [sourceId]. Errors loudly if the
     * key isn't bound — callers either pass a known sourceId from
     * [SourceIds] or look one up from a persisted Fiction row. The
     * old "fall back to the only source" behaviour from #35 was
     * removed in step 8a-i now that multiple sources are bound.
     */
    private fun sourceFor(sourceId: String): FictionSource =
        sources[sourceId]
            ?: error("No FictionSource for id=$sourceId; bound: ${sources.keys}")

    // Issue #382 — small helper to look up `supportsFollow` on the
    // FictionSource the row came from. Returns false for unknown
    // sourceIds (a fiction row whose backend module has since been
    // unbound), which is the safest UI default — hides the Follow
    // button rather than wiring a never-firing path.
    private fun supportsFollowFor(sourceId: String): Boolean =
        sources[sourceId]?.supportsFollow ?: false

    override fun observeLibrary(): Flow<List<FictionSummary>> =
        fictionDao.observeLibrary().map { rows ->
            rows.map { it.toSummary(supportsFollow = supportsFollowFor(it.sourceId)) }
        }

    override fun observeFollowsRemote(): Flow<List<FictionSummary>> =
        fictionDao.observeFollowsRemote().map { rows ->
            rows.map { it.toSummary(supportsFollow = supportsFollowFor(it.sourceId)) }
        }

    override fun observeIsInLibrary(id: String): Flow<Boolean> =
        fictionDao.observe(id)
            .map { it?.inLibrary == true }
            .distinctUntilChanged()

    override fun observeFiction(id: String): Flow<FictionDetail?> =
        fictionDao.observe(id).combine(chapterDao.observeChapterInfosByFiction(id)) { fiction, chapters ->
            fiction?.let {
                FictionDetail(
                    summary = it.toSummary(supportsFollow = supportsFollowFor(it.sourceId)),
                    chapters = chapters.map(::toInfo),
                    genres = it.genres,
                    wordCount = it.wordCount,
                    views = it.views,
                    followers = it.followers,
                    lastUpdatedAt = it.lastUpdatedAt,
                    authorId = it.authorId,
                )
            }
        }

    override suspend fun browsePopular(
        page: Int,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).popular(page))

    override suspend fun browseLatest(
        page: Int,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).latestUpdates(page))

    override suspend fun browseByGenre(
        genre: String,
        page: Int,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).byGenre(genre, page))

    override suspend fun search(
        query: SearchQuery,
        sourceId: String,
    ): FictionResult<ListPage<FictionSummary>> =
        cacheListing(sourceFor(sourceId).search(query))

    override suspend fun genres(sourceId: String): FictionResult<List<String>> =
        sourceFor(sourceId).genres()

    override suspend fun cacheBrowseListing(
        result: FictionResult<ListPage<FictionSummary>>,
    ): FictionResult<ListPage<FictionSummary>> = cacheListing(result)

    override suspend fun refreshDetail(id: String, force: Boolean): FictionResult<Unit> = withContext(Dispatchers.IO) {
        // Look up the persisted row to route to the correct source. When the
        // row is absent — the EPUB/PDF import flows navigate straight to
        // FictionDetail before any row is written (#1298) — derive the source
        // from the fictionId's `<sourceId>:` prefix; bare-id sources (Royal
        // Road) fall back to the legacy default. A successful hydrate below
        // then writes the row, so subsequent loads route off the row.
        val existing = fictionDao.get(id)
        // Issue #1314 — stale-while-revalidate TTL guard. Skip the network when
        // the caller didn't force, we have a fully-hydrated row
        // (metadataFetchedAt > 0 excludes #981 placeholders, which must always
        // fetch), and that hydrate is younger than the TTL. The value flow is a
        // Room observer on the same row, so the already-cached data keeps
        // showing; we just avoid a redundant source round-trip on a rapid
        // re-open. `now - fetchedAt` is robust to clock skew here — a future
        // fetchedAt yields a negative age (< TTL) and still skips, which is the
        // safe direction (don't hammer the source).
        // #1433 — a fiction that claims N chapters in its metadata but has 0
        // rows in the chapter table is in an inconsistent state (process kill,
        // Cloudflare 200 challenge, or empty-parse wipe). Bypass the TTL so the
        // next open re-fetches and repopulates the chapter rows.
        val chaptersOrphan = existing != null && existing.chapterCount > 0 &&
            chapterDao.chapterIdsForFiction(id).isEmpty()
        if (!force && !chaptersOrphan && existing != null && existing.metadataFetchedAt > 0L &&
            System.currentTimeMillis() - existing.metadataFetchedAt < METADATA_TTL_MS
        ) {
            return@withContext FictionResult.Success(Unit)
        }
        val src = sourceFor(existing?.sourceId ?: sourceIdForFictionId(id, sources.keys))
        when (val result = src.fictionDetail(id)) {
            is FictionResult.Success -> {
                upsertDetail(result.value)
                // Issue #981 — a successful hydrate clears any stale
                // back-fill failure stamp so a row that recovered (auth
                // restored, network back, upstream un-404'd) drops its
                // "Couldn't load" state. `upsertDetail` already stamped
                // `metadataFetchedAt = now`, which removes it from the
                // placeholder set; this clears the sibling failure flag.
                fictionDao.clearBackfillFailure(id)
                FictionResult.Success(Unit)
            }
            is FictionResult.Failure -> result
        }
    }

    override suspend fun refreshRemoteFollows(): FictionResult<Unit> = withContext(Dispatchers.IO) {
        // Follows is RR-only today — GitHub source throws on
        // `followsList`. When step 3f wires GitHub PAT auth, this
        // becomes a per-source flow and the kdoc on the interface
        // method should grow a `sourceId` parameter to match.
        val source = sourceFor(SourceIds.ROYAL_ROAD)
        val allIncoming = mutableListOf<FictionSummary>()
        var page = 1
        while (page <= MAX_FOLLOWS_PAGES) {
            when (val result = source.followsList(page = page)) {
                is FictionResult.Success -> {
                    allIncoming.addAll(result.value.items)
                    if (!result.value.hasNext) break
                    page++
                }
                is FictionResult.Failure -> return@withContext result
            }
        }

        val now = System.currentTimeMillis()
        val incomingIds = allIncoming.map { it.id }.toSet()

        allIncoming.forEach { summary ->
            val existing = fictionDao.get(summary.id)
            val merged = if (existing != null) {
                existing.copy(
                    title = summary.title.ifBlank { existing.title },
                    coverUrl = summary.coverUrl ?: existing.coverUrl,
                    tags = summary.tags.ifEmpty { existing.tags },
                    followedRemotely = true,
                )
            } else {
                summary.toEntity(now).copy(followedRemotely = true)
            }
            fictionDao.upsert(merged)
        }

        val previously = fictionDao.followsSnapshot().map { it.id }.toSet()
        (previously - incomingIds).forEach { gone ->
            fictionDao.setFollowedRemote(gone, false)
        }

        FictionResult.Success(Unit)
    }

    override suspend fun addToLibrary(id: String, mode: DownloadMode?) = withContext(Dispatchers.IO) {
        // Ensure the row exists — refresh from source if we don't have it.
        val existing = fictionDao.get(id)
        if (existing == null) refreshDetail(id) // best-effort; ignore failure
        fictionDao.setInLibrary(id, true, System.currentTimeMillis())
        if (mode != null) fictionDao.setDownloadMode(id, mode)
        // PR-F (#86) — notify the playback layer so it can pre-render
        // chapters 1-N (or all, in Mode C). runCatching guards against
        // a listener that throws — library add must always succeed
        // from the user's perspective even if pre-render scheduling
        // hiccups.
        runCatching { libraryListener.onLibraryAdded(id) }
        Unit
    }

    override suspend fun removeFromLibrary(id: String) = withContext(Dispatchers.IO) {
        fictionDao.setInLibrary(id, false, System.currentTimeMillis())
        // Eviction policy: keep the metadata around (no auto-evict per spec).
        // Caller may explicitly purge transient rows via deleteIfTransient.
        fictionDao.deleteIfTransient(id)
        // PR-F (#86) — cancel any background pre-renders for this
        // fiction. Listener is non-suspending so this is fire-and-
        // forget from the repo's perspective; WorkManager's
        // cancelAllWorkByTag is itself async internally.
        runCatching { libraryListener.onLibraryRemoved(id) }
        Unit
    }

    override suspend fun setDownloadMode(id: String, mode: DownloadMode?) {
        fictionDao.setDownloadMode(id, mode)
    }

    override suspend fun setPinnedVoice(id: String, voiceId: String?, locale: String?) {
        fictionDao.setPinnedVoice(id, voiceId, locale)
    }

    override suspend fun pinnedVoiceId(id: String): String? =
        fictionDao.get(id)?.pinnedVoiceId

    override suspend fun setPlaybackSpeed(id: String, speed: Float?) {
        fictionDao.updatePlaybackSpeed(id, speed)
    }

    override fun observePlaybackSpeed(id: String): Flow<Float?> =
        fictionDao.observe(id).map { it?.playbackSpeed }.distinctUntilChanged()

    override suspend fun setFollowedRemote(id: String, followed: Boolean): FictionResult<Unit> =
        withContext(Dispatchers.IO) {
            val src = sourceFor(fictionDao.get(id)?.sourceId ?: sourceIdForFictionId(id, sources.keys))
            when (val r = src.setFollowed(id, followed)) {
                is FictionResult.Success -> {
                    fictionDao.setFollowedRemote(id, followed)
                    FictionResult.Success(Unit)
                }
                is FictionResult.Failure -> r
            }
        }

    override suspend fun markAllCaughtUp(): Int = withContext(Dispatchers.IO) {
        chapterDao.markFollowedCaughtUp(System.currentTimeMillis())
    }

    override suspend fun addByUrl(
        url: String,
        preferredSourceId: String?,
    ): AddByUrlResult = withContext(Dispatchers.IO) {
        // Issue #472 — Resolver-first routing. When the [urlResolver]
        // dep is present (production graph) we walk every plugin's
        // [UrlMatcher]; when absent (legacy unit tests that pre-date
        // the resolver injection) we fall back to the established
        // [UrlRouter] regex bank so RoyalRoad / GitHub still resolve.
        val candidates: List<RouteMatch> = urlResolver?.resolve(url)
            ?: legacyResolveFallback(url)

        if (candidates.isEmpty()) return@withContext AddByUrlResult.UnrecognizedUrl

        // Pick: either the user's preferred source (chooser modal flow)
        // or the highest-confidence candidate. If preferredSourceId is
        // set but no candidate matches it, treat it as a routing bug
        // and fall back to the top candidate — the UI shouldn't be
        // sending an id we don't know about.
        val picked: RouteMatch = preferredSourceId
            ?.let { hint -> candidates.firstOrNull { it.sourceId == hint } }
            ?: maybeMultipleMatchesOr(candidates)
            ?: return@withContext AddByUrlResult.MultipleMatches(candidates)

        val src = sources[picked.sourceId]
            ?: return@withContext AddByUrlResult.UnsupportedSource(picked.sourceId)

        // Pre-write a stub row carrying sourceId so refreshDetail (and any
        // subsequent setFollowedRemote / ChapterDownloadWorker) can route
        // to the right source even before the detail fetch completes. The
        // upsert only seeds fields we know from the URL; richer fields are
        // filled in by upsertDetail on success.
        val now = System.currentTimeMillis()
        if (fictionDao.get(picked.fictionId) == null) {
            fictionDao.upsert(
                Fiction(
                    id = picked.fictionId,
                    sourceId = picked.sourceId,
                    title = "",
                    author = "",
                    firstSeenAt = now,
                    metadataFetchedAt = now,
                    // Issue #989 — remember the original URL for the
                    // hash-id sources (Readability/RSS/EPUB-direct) whose
                    // id can't be reversed to a URL. This is the durable
                    // home that survives process death / cache-clear and
                    // rides across devices via LibrarySyncer.
                    sourceUrl = if (SourceIds.idNeedsSourceUrlToRebuild(picked.sourceId)) url else null,
                ),
            )
        } else if (SourceIds.idNeedsSourceUrlToRebuild(picked.sourceId)) {
            // Row already exists (re-paste of the same URL, or a synced
            // placeholder we're now hydrating): back-fill the URL if we
            // never captured it. setSourceUrlIfAbsent never clobbers.
            fictionDao.setSourceUrlIfAbsent(picked.fictionId, url)
        }

        when (val r = src.fictionDetail(picked.fictionId)) {
            is FictionResult.Success -> {
                upsertDetail(r.value)
                AddByUrlResult.Success(picked.fictionId)
            }
            is FictionResult.Failure -> AddByUrlResult.SourceFailure(r)
        }
    }

    override fun previewUrl(url: String): List<RouteMatch> =
        urlResolver?.resolve(url) ?: legacyResolveFallback(url)

    /**
     * When [urlResolver] is null (legacy unit-test path), walk the
     * pre-#472 [in.jphe.storyvox.data.source.UrlRouter] regex bank so
     * RoyalRoad/GitHub tests still pass. Wrapped to return the same
     * shape ([RouteMatch] list) as the resolver to keep the calling
     * site uniform.
     */
    private fun legacyResolveFallback(url: String): List<RouteMatch> {
        val m = `in`.jphe.storyvox.data.source.UrlRouter.route(url) ?: return emptyList()
        return listOf(
            RouteMatch(
                sourceId = m.sourceId,
                fictionId = m.fictionId,
                confidence = 1.0f,
                label = m.sourceId,
            ),
        )
    }

    /**
     * Returns the single best candidate when one route dominates, or
     * null when the chooser modal should appear. "Dominates" = top
     * candidate is at least [DOMINANT_GAP] more confident than the
     * second-best candidate, OR is the only chooser-eligible entry
     * (≥0.5). The Readability catch-all (0.1) doesn't count as a
     * competing route — when it sits beneath a single mid/high
     * confidence backend, that backend wins outright.
     */
    private fun maybeMultipleMatchesOr(candidates: List<RouteMatch>): RouteMatch? {
        val top = candidates.firstOrNull() ?: return null
        val chooserEligible = candidates.filter { it.confidence >= CHOOSER_THRESHOLD }
        // Single chooser-eligible entry (or none — Readability-only
        // case) → top wins outright.
        if (chooserEligible.size <= 1) return top
        // Two+ chooser-eligible entries: dominance test on the top
        // pair. If top is meaningfully more confident than runner-up,
        // pick it; otherwise surface the chooser.
        val runnerUp = chooserEligible[1]
        return if (top.confidence - runnerUp.confidence >= DOMINANT_GAP) top else null
    }

    private companion object {
        const val CHOOSER_THRESHOLD: Float = 0.5f
        const val DOMINANT_GAP: Float = 0.2f
        const val MAX_FOLLOWS_PAGES: Int = 200

        /**
         * Issue #1314 — stale-while-revalidate window for [refreshDetail]. A
         * non-forced refresh of a row hydrated within this window skips the
         * network. Five minutes covers the "rapid re-open of a just-viewed
         * fiction" case the guard targets without letting genuinely stale
         * metadata linger — background workers (poll / back-fill) and the
         * manual Retry button pass `force = true` to bypass it entirely.
         */
        const val METADATA_TTL_MS: Long = 5 * 60 * 1000L
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private suspend fun cacheListing(
        result: FictionResult<ListPage<FictionSummary>>,
    ): FictionResult<ListPage<FictionSummary>> {
        if (result is FictionResult.Success) {
            val now = System.currentTimeMillis()
            fictionDao.upsertAllPreservingUserState(result.value.items.map { it.toEntity(now) })
        }
        return result
    }

    // #1437 — wrap both DB writes in a Room transaction so a process
    // kill between the fiction upsert and the chapter upsert cannot
    // orphan chapters. Falls back to non-transactional for unit tests
    // that construct the repo without a real Room database.
    private suspend fun upsertDetail(detail: FictionDetail) {
        if (db != null) db.withTransaction { upsertDetailInner(detail) }
        else upsertDetailInner(detail)
    }

    private suspend fun upsertDetailInner(detail: FictionDetail) {
        val now = System.currentTimeMillis()
        val existing = fictionDao.get(detail.summary.id)
        fictionDao.upsert(detail.toEntity(existing, now))

        // #1433 — guard: an empty incoming chapter list from a Cloudflare
        // challenge page (HTTP 200 with JS challenge), a truncated response,
        // or a process kill mid-parse would wipe every existing chapter via
        // the DELETE-then-INSERT in upsertChaptersForFiction. Skip the chapter
        // upsert entirely when the parse returned nothing — the existing
        // chapter rows (if any) are still valid and will be refreshed on the
        // next successful detail fetch.
        if (detail.chapters.isEmpty()) return

        // Upsert chapter rows; preserve body + download state for chapters we
        // already have, drop in fresh metadata for new ones.
        val incoming = detail.chapters.map { it.toEntity(detail.summary.id) }
        val merged = incoming.map { fresh ->
            val previous = chapterDao.get(fresh.id)
            if (previous == null) fresh else fresh.copy(
                htmlBody = previous.htmlBody,
                plainBody = previous.plainBody,
                bodyFetchedAt = previous.bodyFetchedAt,
                bodyChecksum = previous.bodyChecksum,
                downloadState = previous.downloadState,
                lastDownloadAttemptAt = previous.lastDownloadAttemptAt,
                lastDownloadError = previous.lastDownloadError,
                userMarkedRead = previous.userMarkedRead,
                firstReadAt = previous.firstReadAt,
                audioUrl = fresh.audioUrl ?: previous.audioUrl,
            )
        }
        chapterDao.upsertChaptersForFiction(detail.summary.id, merged)
    }
}

// ─── mappers ──────────────────────────────────────────────────────────────

internal fun Fiction.toSummary(supportsFollow: Boolean = false): FictionSummary = FictionSummary(
    id = id,
    sourceId = sourceId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    tags = tags,
    status = status,
    chapterCount = chapterCount,
    rating = rating,
    followedRemotely = followedRemotely,
    supportsFollow = supportsFollow,
    addedAt = addedToLibraryAt,
    // Issue #981 — a synced row that hasn't been hydrated yet has
    // metadataFetchedAt == 0 and the sentinel "Loading…" title; the
    // MetadataBackfillWorker resolves these in the background. The
    // failure stamp drives the "Couldn't load" UI state.
    isPlaceholder = metadataFetchedAt == 0L,
    backfillFailed = metadataBackfillFailedAt != null,
)

internal fun FictionSummary.toEntity(now: Long): Fiction = Fiction(
    id = id,
    sourceId = sourceId,
    title = title,
    author = author,
    coverUrl = coverUrl,
    description = description,
    tags = tags,
    status = status,
    chapterCount = chapterCount ?: 0,
    rating = rating,
    firstSeenAt = now,
    metadataFetchedAt = now,
)

internal fun FictionDetail.toEntity(existing: Fiction?, now: Long): Fiction {
    val base = existing ?: summary.toEntity(now)
    // Issue #1023 — the "Loading…" placeholder sentinel is NOT a real
    // cached title; treat it as blank everywhere below so it can neither
    // win over an incoming title in [preferTitle] nor count as "we have a
    // good title now". The #279 guard was written for `existing` being a
    // genuinely-good cached title we don't want to clobber; it never
    // anticipated `existing` being the placeholder sentinel.
    val existingTitle = base.title.takeUnless { it == Fiction.PLACEHOLDER_TITLE }.orEmpty()
    val resolvedTitle = preferTitle(
        incoming = summary.title,
        // Issue #279 — never overwrite a previously-good title with a
        // worse one. The RSS source falls back to the URL host when the
        // feed parse comes up blank (intermittent gateway timeouts,
        // momentarily-malformed XML, upstream 5xx, etc.), which produced a
        // perfectly non-blank but useless string like "lionsroar.com". The
        // result: pull-to-refresh silently corrupted the Library card from
        // 'Lion's Roar / Rev. Marvin Harada' to 'lionsroar.com / ?'.
        existing = existingTitle,
        sourceFallback = inferUrlHost(summary.description),
    )
    // Issue #1023 — only treat this as a completed hydrate (stamp
    // metadataFetchedAt = now, which drops the row out of
    // `placeholdersToBackfill`) when we actually resolved a real title. A
    // *successful* fetch that still yields a blank/sentinel title is a soft
    // failure: leave `metadataFetchedAt` as-is (0 for a placeholder) so the
    // back-fill worker's cool-down retry applies, exactly like the
    // FictionResult.Failure path #981 established — rather than freezing the
    // sentinel as the permanent title with no spinner, no error, no retry.
    val hydrated = resolvedTitle.isNotBlank() && resolvedTitle != Fiction.PLACEHOLDER_TITLE
    return base.copy(
        title = resolvedTitle,
        author = summary.author.ifBlank { base.author },
        authorId = authorId ?: base.authorId,
        coverUrl = summary.coverUrl ?: base.coverUrl,
        description = summary.description ?: base.description,
        genres = genres,
        tags = summary.tags.ifEmpty { base.tags },
        status = summary.status,
        chapterCount = if (chapters.isNotEmpty()) chapters.size else base.chapterCount,
        wordCount = wordCount ?: base.wordCount,
        rating = summary.rating ?: base.rating,
        views = views ?: base.views,
        followers = followers ?: base.followers,
        lastUpdatedAt = lastUpdatedAt ?: base.lastUpdatedAt,
        metadataFetchedAt = if (hydrated) now else base.metadataFetchedAt,
    )
}

/**
 *  Issue #279 — title-degradation guard for [FictionDetail.toEntity].
 *
 *  Returns the incoming title unless it looks like a degraded source
 *  fallback (blank, OR equal to the URL host extracted from the same
 *  detail's description). In the degraded case we keep the existing
 *  cached title — assuming we have one. First-add flows where the
 *  existing row is the just-created stub get the incoming title verbatim
 *  (the stub's title is also derived from the same source, so there's
 *  no "better" alternative to preserve).
 *
 *  The host-equality check is what catches the [RssSource] failure mode
 *  specifically: when `feed.title.isBlank()`, RSS returns
 *  `displayLabelForUrl(sub.url)` which is `host.removePrefix("www.")`.
 *  That string is structurally distinguishable from a real title
 *  ("lionsroar.com" vs "Lion's Roar"), and from a real description, so
 *  catching it here rather than asking every source to opt in keeps the
 *  guard durable.
 */
/**
 * Issue #1298 — derive the backing source for a fictionId that has no
 * persisted row yet. Most sources prefix their ids with `<sourceId>:`
 * (`epub:`, `pdf:`, `gutenberg:`, `ao3:`, `ocr:`, `readability:`, …); Royal
 * Road uses bare numeric ids. Returns the prefix when it names a
 * currently-bound source, else falls back to [SourceIds.ROYAL_ROAD] — never
 * an unbound prefix, since [FictionRepositoryImpl.sourceFor] errors on an
 * unknown source id.
 *
 * Why this exists: the EPUB/PDF import flows (Open-With #1000 and the in-app
 * picker #1228) navigate straight to FictionDetail, so `refreshDetail` runs
 * before any row is written. Defaulting to Royal Road there sent
 * `epub:<hash>` to RoyalRoadSource → "Fiction epub:<hash> not found".
 */
internal fun sourceIdForFictionId(id: String, boundSourceIds: Set<String>): String =
    id.substringBefore(':', missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() && it in boundSourceIds }
        ?: SourceIds.ROYAL_ROAD

internal fun preferTitle(incoming: String, existing: String, sourceFallback: String?): String {
    if (incoming.isBlank()) return existing.ifBlank { incoming }
    if (existing.isBlank()) return incoming
    if (sourceFallback != null && incoming.equals(sourceFallback, ignoreCase = true)) {
        return existing
    }
    return incoming
}

/** Issue #279 — pull a bare host out of a URL-shaped string. Used as
 *  the second axis of the title-degradation check in [preferTitle].
 *  Returns null when the input doesn't parse as a URI or has no host
 *  (so [preferTitle] falls back to the trust-the-incoming branch). */
internal fun inferUrlHost(maybeUrl: String?): String? {
    if (maybeUrl.isNullOrBlank()) return null
    return runCatching {
        java.net.URI(maybeUrl).host?.removePrefix("www.")
    }.getOrNull()
}

internal fun ChapterInfo.toEntity(fictionId: String): Chapter = Chapter(
    id = id,
    fictionId = fictionId,
    sourceChapterId = sourceChapterId,
    index = index,
    title = title,
    publishedAt = publishedAt,
    wordCount = wordCount,
    downloadState = ChapterDownloadState.NOT_DOWNLOADED,
    audioUrl = audioUrl,
)

internal fun Chapter.toInfo(): ChapterInfo = ChapterInfo(
    id = id,
    sourceChapterId = sourceChapterId,
    index = index,
    title = title,
    publishedAt = publishedAt,
    wordCount = wordCount,
    audioUrl = audioUrl,
)

internal fun toInfo(row: `in`.jphe.storyvox.data.db.dao.ChapterInfoRow): ChapterInfo = ChapterInfo(
    id = row.id,
    sourceChapterId = row.sourceChapterId,
    index = row.index,
    title = row.title,
    publishedAt = row.publishedAt,
    wordCount = row.wordCount,
)
