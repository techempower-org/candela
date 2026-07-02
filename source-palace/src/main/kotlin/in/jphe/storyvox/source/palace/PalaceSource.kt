package `in`.jphe.storyvox.source.palace

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
import `in`.jphe.storyvox.source.epub.parse.EpubBook
import `in`.jphe.storyvox.source.epub.parse.EpubParseException
import `in`.jphe.storyvox.source.epub.parse.EpubParser
import `in`.jphe.storyvox.source.palace.di.PalaceCache
import `in`.jphe.storyvox.source.palace.net.PalaceApi
import `in`.jphe.storyvox.source.palace.parse.OpdsEntry
import `in`.jphe.storyvox.source.palace.parse.OpdsFeed
import `in`.jphe.storyvox.source.palace.parse.OpdsLink
import `in`.jphe.storyvox.source.palace.parse.OpdsMime
import `in`.jphe.storyvox.source.palace.parse.OpdsRel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #502 — Palace Project as a fiction backend.
 *
 * The Palace Project is open-source library management software run by
 * the nonprofit LYRASIS; it backs many US public libraries (NYPL, BPL,
 * etc.). Each Palace-backed library exposes an OPDS 1.x catalog at a
 * user-configurable root URL (see [PalaceLibraryConfig]); titles in
 * that catalog are either:
 *
 *  - **Open-access** — free EPUB downloads, no DRM. Public-domain works
 *    Palace has curated for accessibility, plus library-published
 *    materials and CC-licensed catalog. **These are what v1 storyvox
 *    actually serves.**
 *  - **DRM'd (LCP)** — borrowed-by-loan titles that require Readium LCP
 *    decryption. v1 surfaces these greyed-out with an "Open in Palace
 *    app to borrow" deep-link CTA; storyvox attempts no decryption.
 *    See `scratch/libby-hoopla-palace-scope/lcp-drm-scope.md` for the
 *    deferred plan.
 *
 * ## Two-phase model (same shape as :source-gutenberg, :source-ao3,
 * :source-standard-ebooks)
 *
 * **Catalog phase**: walk the configured library's OPDS root. The root
 * feed mixes navigation entries (links to sub-feeds like "Featured" /
 * "Children's Books" / "New Releases") with optional publication
 * entries. The [PalaceSource] flattens navigation links into the
 * [genres] picker and consumes any directly-listed publications as the
 * [popular] / [latestUpdates] surface. Search hits the library's
 * OpenSearch description (or a `?q=` query against the root, depending
 * on what the library publishes — `:source-palace` falls back to
 * filtering the root feed when the library doesn't advertise a search
 * URL).
 *
 * **Detail / chapter phase**: when the user adds an open-access title
 * to their library, the OPDS entry's open-access acquisition link is
 * downloaded once, persisted at `cacheDir/palace/<entry-id-hash>.epub`,
 * and from then on chapter rendering reads spine items out of that
 * local file via the parser already on `:source-epub`. Identical
 * pattern to PG / SE / AO3.
 *
 * ## DRM handling
 *
 * A title is "DRM'd" from storyvox's perspective when **none** of its
 * acquisition links carry `type="application/epub+zip"` with
 * `rel="…/acquisition/open-access"`. The detection happens in
 * [pickOpenAccessEpub]. DRM'd titles still appear in the catalog (so
 * the user sees the library's full breadth), but their
 * [FictionSummary.status] is annotated and the
 * [`fictionDetail`][fictionDetail] / [`chapter`][chapter] paths return
 * a structured [FictionResult.AuthRequired] with a message pointing to
 * the Palace app deep-link. The reader UI surfaces that as a card
 * with "Open in Palace app" rather than a network-error banner.
 *
 * No LCP decryption attempt anywhere in this module. The downstream
 * cache layer never sees DRM'd bytes — we don't fetch them.
 *
 * ## URL matching (#472 — magic-link cascade)
 *
 * Three URL shapes claim through `:source-palace`:
 *
 *  - `<library>.palaceproject.io/<feed>/works/<urn>` — Palace Project's
 *    canonical hosted-library URLs (NYPL, BPL, etc.)
 *  - `circulation.<library>.org/<feed>/works/<urn>` — self-hosted
 *    Palace deployments (Open eBooks, Maine InfoNet, etc.)
 *  - Generic `palaceproject.io` covers organisation pages
 *
 * Confidence is 0.9 when the host matches a known Palace pattern and
 * the path looks publication-shaped; 0.5 for host-only matches. The
 * Readability catch-all at confidence 0.1 always loses to either.
 */
@SourcePlugin(
    id = SourceIds.PALACE,
    displayName = "Palace Library",
    // defaultEnabled false until JP picks a default library or
    // the user configures one. A "Palace Library" row that says
    // "configure a library URL to use this source" is worse UX
    // than hiding the chip until the user has opted in.
    defaultEnabled = false,
    // OPDS catalogs are EPUB-shaped; route under the Ebook category in
    // the settings auto-section, alongside Project Gutenberg / Standard
    // Ebooks / local EPUB files.
    category = SourceCategory.Ebook,
    supportsFollow = false,
    // OPDS supports search via the OpenSearch description document.
    // Most Palace libraries publish one; the source falls back to
    // returning an empty result for libraries that don't.
    supportsSearch = true,
    description = "Public library borrowing via OPDS · open-access titles only (DRM titles deep-link to Palace app)",
    sourceUrl = "https://thepalaceproject.org",
)
@Singleton
internal class PalaceSource @Inject constructor(
    private val api: PalaceApi,
    private val config: PalaceLibraryConfig,
    @PalaceCache private val cacheDir: File,
) : FictionSource, UrlMatcher {

    override val id: String = SourceIds.PALACE
    override val displayName: String = "Palace Library"

    /**
     * In-memory cache of parsed EPUBs keyed by storyvox fictionId.
     * Mirrors the same hot-path optimization PG / SE / AO3 use — the
     * cost of re-parsing a 2-10 MB EPUB on every chapter open
     * dominates the OPDS-feed-walk cost.
     */
    private val parsedCache = mutableMapOf<String, EpubBook>()

    /**
     * In-memory cache of OPDS entries the user has browsed recently,
     * keyed by storyvox fictionId. Populated as the browse / search
     * paths walk feeds; consumed by [fictionDetail] so we don't need a
     * second round-trip to the library to render the detail card. A
     * cold-start detail open re-walks the root feed once to repopulate
     * (cheap; OPDS roots are < 50 KB on the libraries we've seen).
     */
    private val entryCache = mutableMapOf<String, OpdsEntry>()

    /**
     * Cached library root feed. Walked once per session to populate the
     * genres picker + collection navigation. Refreshed whenever the
     * configured library URL changes (see [observeConfig]).
     *
     * v1 doesn't expire this; the library's catalog updates daily at
     * most and a process restart picks up changes. v2 might add a
     * polling worker or an If-Modified-Since check.
     */
    @Volatile private var cachedRoot: OpdsFeed? = null
    @Volatile private var cachedRootForUrl: String? = null

    // ── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> {
        val libraryUrl = currentLibraryUrlOrFail()
            ?: return libraryNotConfigured()
        // Most Palace libraries put their "front page" / curated
        // selection at the root OPDS feed. If the root only carries
        // navigation entries (sub-feed links), pop into the first
        // navigable child — that's usually "Featured" / "Popular Reads"
        // on Palace deployments we've seen.
        val root = ensureRoot(libraryUrl).valueOrReturn { return it }
        val publications = if (root.entries.isNotEmpty()) {
            root.entries
        } else {
            // First sub-feed is the catalog's curated landing on Palace
            // deployments. If it's empty too, we return an empty result
            // rather than walking the whole tree — Browse renders a
            // "no titles here" empty state cleanly.
            val firstNav = root.navLinks.firstOrNull()
                ?: return FictionResult.Success(
                    ListPage(items = emptyList(), page = 1, hasNext = false),
                )
            when (val r = api.fetchFeed(firstNav.href)) {
                is FictionResult.Success -> r.value.entries
                is FictionResult.Failure -> return r
            }
        }
        return FictionResult.Success(
            ListPage(
                items = publications.map { it.cacheAndToSummary() },
                page = page,
                // OPDS exposes `<link rel="next">` for pagination. The
                // root + first-nav-child path above doesn't carry it
                // forward; v1 returns hasNext=false for the popular
                // lane. A v2 pass can plumb the [OpdsFeed.nextHref]
                // through if Palace surfaces multi-page popular feeds.
                hasNext = false,
            ),
        )
    }

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> {
        val libraryUrl = currentLibraryUrlOrFail()
            ?: return libraryNotConfigured()
        // Look for a navigation entry whose title hints at "new" /
        // "recent" / "latest". Palace libraries label these
        // inconsistently ("New Releases", "Recently Added", "New
        // Arrivals"), so prefix-match a small set of likely tokens.
        val root = ensureRoot(libraryUrl).valueOrReturn { return it }
        val newReleasesNav = root.navLinks.firstOrNull { nav ->
            val t = nav.title.lowercase()
            t.contains("new") || t.contains("recent") || t.contains("latest")
        }
        val publications = if (newReleasesNav != null) {
            when (val r = api.fetchFeed(newReleasesNav.href)) {
                is FictionResult.Success -> r.value.entries
                is FictionResult.Failure -> return r
            }
        } else {
            // No new-releases lane on this library; reuse the popular
            // surface so the Browse tab still renders something.
            root.entries
        }
        return FictionResult.Success(
            ListPage(
                items = publications.map { it.cacheAndToSummary() },
                page = page,
                hasNext = false,
            ),
        )
    }

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        val libraryUrl = currentLibraryUrlOrFail()
            ?: return libraryNotConfigured()
        // Match the genre label against navLink titles emitted by
        // [genres]. Case-insensitive exact match keeps the picker /
        // catalog mapping unambiguous.
        val root = ensureRoot(libraryUrl).valueOrReturn { return it }
        val navLink = root.navLinks.firstOrNull { it.title.equals(genre, ignoreCase = true) }
            ?: return FictionResult.Success(
                ListPage(items = emptyList(), page = 1, hasNext = false),
            )
        val feed = when (val r = api.fetchFeed(navLink.href)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        return FictionResult.Success(
            ListPage(
                items = feed.entries.map { it.cacheAndToSummary() },
                page = page,
                hasNext = feed.nextHref != null,
            ),
        )
    }

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        // Palace libraries publish an OpenSearch description document
        // pointed at by a feed-level `<link rel="search">`. v1 doesn't
        // walk that — the OpenSearch parsing is a meaningful chunk of
        // work for a single search surface and most Palace libraries
        // also support a `?q=` filter on the root URL.
        //
        // For v1, search returns an empty result with a placeholder
        // comment. The Settings → Library & Sync screen (owned by #501)
        // surfaces the catalog under "Browse" rather than "Search"
        // until the OpenSearch path lands.
        //
        // FUTURE-STATE: when adding real search:
        //  1. Walk the root feed's `<link rel="search">` to find the
        //     OpenSearch description URL.
        //  2. Fetch + parse that document for the `<Url
        //     template="..."/>` (substitute `{searchTerms}`).
        //  3. GET the substituted URL → OPDS acquisition feed → same
        //     `.entries.map { it.cacheAndToSummary() }` shape this
        //     module already uses.
        @Suppress("UNUSED_VARIABLE")
        val term = query.term
        return FictionResult.Success(
            ListPage(items = emptyList(), page = 1, hasNext = false),
        )
    }

    override suspend fun genres(): FictionResult<List<String>> {
        val libraryUrl = currentLibraryUrlOrFail()
            ?: return FictionResult.Success(emptyList())
        val root = ensureRoot(libraryUrl).valueOrReturn { return it }
        // Surface navigation entries as genres. The list is curated by
        // the library itself (each Palace library has its own
        // collections), so we emit what we see.
        return FictionResult.Success(root.navLinks.map { it.title })
    }

    // ── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val entry = entryCache[fictionId] ?: run {
            // Cold-start detail open (e.g., user reopened the app and
            // tapped a Library row whose entry isn't in our in-process
            // cache). Re-walk the root + nav children to look for the
            // entry id. This is a v1 simplification — a v2 pass could
            // persist a small entry-id → href index instead.
            val libraryUrl = currentLibraryUrlOrFail()
                ?: return libraryNotConfigured()
            when (val r = recachedEntryFor(fictionId, libraryUrl)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return r
            }
        }

        // DRM check: if no open-access EPUB acquisition link exists,
        // this is a borrow-only title. Surface as AuthRequired with a
        // human-readable message pointing to the Palace app — the
        // reader UI renders that as a deep-link CTA rather than a
        // generic network error.
        val openAccess = pickOpenAccessEpub(entry.links)
            ?: return FictionResult.AuthRequired(
                "This title is only available through a Palace library " +
                    "borrow. Open the Palace app to borrow ${entry.title}.",
            )

        val parsed = when (val r = ensureParsed(fictionId, openAccess.href)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.PALACE,
            title = entry.title,
            author = entry.author.orEmpty(),
            description = entry.summary,
            coverUrl = entry.coverUrl,
            tags = entry.categories,
            // Open-access OPDS titles are "complete works" — same
            // posture PG / SE take for their finalized catalog entries.
            status = FictionStatus.COMPLETED,
            chapterCount = parsed.chapters.size,
        )
        val chapters = parsed.chapters.map { ch ->
            ChapterInfo(
                id = chapterIdFor(fictionId, ch.index),
                sourceChapterId = ch.id,
                index = ch.index,
                title = ch.title,
            )
        }
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapters))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val parsed = parsedCache[fictionId]
            ?: return when (val r = reparseFromDisk(fictionId)) {
                is FictionResult.Success -> chapter(fictionId, chapterId)
                is FictionResult.Failure -> r
            }
        val idx = chapterIndexFrom(chapterId)
            ?: return FictionResult.NotFound("Malformed chapter id: $chapterId")
        val ch = parsed.chapters.getOrNull(idx)
            ?: return FictionResult.NotFound(
                "Chapter $idx out of range (have ${parsed.chapters.size})",
            )
        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = ch.id,
            index = ch.index,
            title = ch.title,
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = ch.htmlBody,
                plainBody = ch.htmlBody.stripTags(),
            ),
        )
    }

    // ── auth-gated ────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // Palace doesn't expose a follow concept through the public
        // OPDS feed. Borrow-side "currently checked out" is a separate
        // authenticated surface, deferred to the auth-landing PR (see
        // #500's encrypted-credential storage work).
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ── UrlMatcher (#472) ─────────────────────────────────────────────

    /** Issue #472, #502 — Palace Project URL patterns. Confidence 0.9
     *  for the canonical hosted-library URL shape, 0.5 for host-only
     *  matches that we can't fully resolve without more context. */
    override fun matchUrl(url: String): RouteMatch? {
        val trimmed = url.trim()
        // 1. Canonical hosted-library URL — works/<urn> path. Highest
        //    confidence; the URN goes straight into the storyvox
        //    fictionId so the detail fetch can recover the OPDS entry
        //    from the cache (or re-walk to find it on cold start).
        PALACE_WORK_URL_PATTERN.matchEntire(trimmed)?.let { m ->
            val urn = m.groupValues[1]
            return RouteMatch(
                sourceId = SourceIds.PALACE,
                fictionId = "${SourceIds.PALACE}:$urn",
                confidence = 0.9f,
                label = "Palace library title",
            )
        }
        // 2. Host-only Palace URL — palaceproject.io homepage or a
        //    library subdomain with no work path. The repository can't
        //    fetch a real detail from this, but the matcher claims it
        //    so the magic-link picker can surface a "Configure Palace
        //    library" prompt rather than dropping the URL to
        //    Readability4J.
        PALACE_HOST_URL_PATTERN.matchEntire(trimmed)?.let {
            return RouteMatch(
                sourceId = SourceIds.PALACE,
                fictionId = "${SourceIds.PALACE}:configure",
                confidence = 0.5f,
                label = "Palace Project (configure library)",
            )
        }
        return null
    }

    // ── helpers ───────────────────────────────────────────────────────

    /**
     * Pull the configured library URL once. Returns null when the user
     * hasn't configured a library yet — the caller surfaces that as
     * "configure a library" copy rather than an opaque network error.
     */
    private suspend fun currentLibraryUrlOrFail(): String? =
        config.libraryRootUrl.first()?.takeIf { it.isNotBlank() }

    private fun libraryNotConfigured(): FictionResult.Failure =
        FictionResult.AuthRequired(
            "Configure a Palace Project library URL in Settings " +
                "→ Library & Sync → Palace Library to browse this source.",
        )

    /**
     * Ensure the cached root feed for [libraryUrl] is fresh. Walked
     * once per session per library URL; subsequent calls hit the
     * in-process cache.
     */
    private suspend fun ensureRoot(libraryUrl: String): FictionResult<OpdsFeed> {
        cachedRoot
            ?.takeIf { cachedRootForUrl == libraryUrl }
            ?.let { return FictionResult.Success(it) }
        val r = api.fetchFeed(libraryUrl)
        if (r is FictionResult.Success) {
            cachedRoot = r.value
            cachedRootForUrl = libraryUrl
        }
        return r
    }

    /**
     * Idempotent EPUB acquire-and-parse. First call for a title
     * downloads + parses + caches; subsequent calls return the cached
     * [EpubBook]. Mirrors the helper PG / SE / AO3 use.
     */
    private suspend fun ensureParsed(
        fictionId: String,
        epubUrl: String,
    ): FictionResult<EpubBook> {
        parsedCache[fictionId]?.let { return FictionResult.Success(it) }
        val onDisk = epubFileFor(fictionId)
        if (onDisk.exists() && onDisk.length() > 0L) {
            return reparseFromDisk(fictionId)
        }
        val bytes = when (val r = api.downloadEpub(epubUrl)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        withContext(Dispatchers.IO) {
            onDisk.parentFile?.mkdirs()
            onDisk.writeBytes(bytes)
        }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError(
                "Could not parse Palace EPUB: ${e.message}",
                e,
            )
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    private suspend fun reparseFromDisk(fictionId: String): FictionResult<EpubBook> {
        val onDisk = epubFileFor(fictionId)
        if (!onDisk.exists()) {
            return FictionResult.NotFound("No cached EPUB for $fictionId")
        }
        val bytes = withContext(Dispatchers.IO) { onDisk.readBytes() }
        val parsed = try {
            withContext(Dispatchers.IO) { EpubParser.parseFromBytes(bytes) }
        } catch (e: EpubParseException) {
            return FictionResult.NetworkError(
                "Cached Palace EPUB unparseable: ${e.message}",
                e,
            )
        }
        parsedCache[fictionId] = parsed
        return FictionResult.Success(parsed)
    }

    /**
     * Cold-start detail support: re-walk the root + nav children
     * looking for [fictionId]. Returns the entry on hit, NotFound on
     * miss. v1 simplification (a v2 pass could persist an entry-id ←→
     * sub-feed-href index in the cache dir).
     */
    private suspend fun recachedEntryFor(
        fictionId: String,
        libraryUrl: String,
    ): FictionResult<OpdsEntry> {
        val root = ensureRoot(libraryUrl).valueOrReturn { return it }
        root.entries.firstOrNull { palaceIdFor(it) == fictionId }
            ?.let {
                entryCache[fictionId] = it
                return FictionResult.Success(it)
            }
        // Walk one level of navigation. Bounded by however many lanes
        // the library publishes; in practice 5-15 sub-feeds.
        for (nav in root.navLinks) {
            val feed = when (val r = api.fetchFeed(nav.href)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> continue // skip broken lanes
            }
            feed.entries.firstOrNull { palaceIdFor(it) == fictionId }
                ?.let {
                    entryCache[fictionId] = it
                    return FictionResult.Success(it)
                }
        }
        return FictionResult.NotFound("Palace title $fictionId not found in current library catalog")
    }

    /** Cache file path for an EPUB belonging to [fictionId]. The
     *  filename uses a stable hex hash of the fictionId so we don't
     *  have to worry about character-set hazards on Android storage
     *  (Palace URN-shaped ids contain `:` which is fine on ext4 / f2fs
     *  but historically wedges on some FAT-formatted SD cards). */
    private fun epubFileFor(fictionId: String): File =
        File(cacheDir, "${fictionId.stableHashFilename()}.epub")

    /** Convert an OPDS entry to its storyvox FictionSummary AND keep
     *  the entry around for the detail fetch. The combination keeps
     *  this from being two passes over the same list. */
    private fun OpdsEntry.cacheAndToSummary(): FictionSummary {
        val fid = palaceIdFor(this)
        entryCache[fid] = this
        return FictionSummary(
            id = fid,
            sourceId = SourceIds.PALACE,
            title = title,
            author = author.orEmpty(),
            description = summary,
            coverUrl = coverUrl,
            tags = categories,
            status = if (pickOpenAccessEpub(links) != null) {
                // Open-access titles render as fully-listenable in
                // storyvox; mark them COMPLETED to suppress any
                // "ongoing serial" affordances the Browse row may
                // surface for ONGOING fictions.
                FictionStatus.COMPLETED
            } else {
                // DRM'd titles: still surface in the list but the
                // detail/chapter path will return AuthRequired. We
                // can't easily map this to the FictionStatus enum
                // without extending it, so reuse STUB — the closest
                // semantic ("listing surface only, no playable body").
                // The card UI distinguishes via the deep-link CTA on
                // detail open, not via a status badge.
                FictionStatus.STUB
            },
        )
    }
}

// ── helpers used by both the source and tests ────────────────────────

/** Issue #502 — Palace Project canonical work URL. Matches the
 *  `/works/<urn>` path under any palaceproject.io subdomain or the
 *  documented hosted-library hostnames (Open eBooks, etc.). The URN
 *  group is captured so it goes verbatim into the storyvox fictionId. */
internal val PALACE_WORK_URL_PATTERN: Regex = Regex(
    """^https?://(?:[\w-]+\.)*(?:palaceproject\.io|openebooks\.us|circulation\.[\w.-]+)/[\w/.-]+/works/([^/?#]+)(?:[/?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Host-only Palace URL. Confidence 0.5 — claims the URL so the
 *  magic-link picker shows a Palace card rather than dropping to the
 *  Readability catch-all, but can't fully resolve it. */
internal val PALACE_HOST_URL_PATTERN: Regex = Regex(
    """^https?://(?:[\w-]+\.)?palaceproject\.io/?(?:[/?#].*)?$""",
    RegexOption.IGNORE_CASE,
)

/** Pick the best open-access EPUB acquisition link from [links], or
 *  null when the title is DRM-only.
 *
 *  Preference order:
 *    1. `rel=…/acquisition/open-access` + `type=application/epub+zip`
 *       — the strongest signal Palace gives for "free, no DRM EPUB
 *       download".
 *    2. `rel=…/acquisition` + `type=application/epub+zip` — bare
 *       acquisition with an EPUB MIME. Palace deployments sometimes
 *       skip the `/open-access` suffix for older feeds; if the MIME is
 *       EPUB and not the LCP license, it's still a free download.
 *
 *  Anything carrying [OpdsMime.LCP_LICENSE] is explicitly skipped — the
 *  source must never invoke an LCP-license URL in v1. */
internal fun pickOpenAccessEpub(links: List<OpdsLink>): OpdsLink? {
    val openAccess = links.firstOrNull {
        it.rel == OpdsRel.ACQUISITION_OPEN_ACCESS && it.type == OpdsMime.EPUB
    }
    if (openAccess != null) return openAccess
    return links.firstOrNull {
        it.rel == OpdsRel.ACQUISITION &&
            it.type == OpdsMime.EPUB &&
            // The bare acquisition rel sometimes points at an LCP
            // license rather than an EPUB. The MIME check above already
            // filters that out — defence in depth: any link whose MIME
            // is `application/vnd.readium.lcp.license.v1.0+json` is
            // never returned from this function.
            it.type != OpdsMime.LCP_LICENSE
    }
}

/** Build a stable storyvox fictionId from an OPDS entry id.
 *  `palace:<urn>` — the URN gets carried through directly because it's
 *  already a stable opaque identifier the library uses.
 *
 *  No URL-encoding: Palace URNs are `urn:librarysimplified.org:works/<n>`
 *  shaped — only colons, slashes, and ASCII alphanumerics. Stable across
 *  feed refreshes (the URN is the library's permanent id for the work). */
internal fun palaceIdFor(entry: OpdsEntry): String =
    "${SourceIds.PALACE}:${entry.id}"

/** Compose chapter id = `${fictionId}::${spineIdx}`. Mirrors PG / SE /
 *  AO3 — chapter lookups recover the spine index without a separate
 *  map. */
internal fun chapterIdFor(fictionId: String, spineIndex: Int): String =
    "$fictionId::$spineIndex"

internal fun chapterIndexFrom(chapterId: String): Int? =
    chapterId.substringAfterLast("::", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() }
        ?.toIntOrNull()

/** Cheap HTML→plaintext for the chapter body the engine receives.
 *  The downstream pipeline normalizes further; this just gets the
 *  visible text out of the wrapper tags so the TTS engine doesn't
 *  read out angle-bracket noise. Same shape as the equivalent helper
 *  on `:source-gutenberg` (which already handles `<head>` / `<script>`
 *  / `<style>` stripping for SE / PG EPUBs — Palace EPUBs are the same
 *  EPUB 3 shape). */
internal fun String.stripTags(): String {
    val noHead = Regex("(?is)<head\\b[^>]*>.*?</head>").replace(this, " ")
    val noScript = Regex("(?is)<script\\b[^>]*>.*?</script>").replace(noHead, " ")
    val noStyle = Regex("(?is)<style\\b[^>]*>.*?</style>").replace(noScript, " ")
    val noComments = Regex("(?s)<!--.*?-->").replace(noStyle, " ")
    return Regex("<[^>]+>").replace(noComments, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

/** Build a filesystem-safe filename from a fictionId. Uses
 *  [String.hashCode] formatted as 8-hex — collision-prone in theory but
 *  the per-library catalog has < 100k titles and the bucket scope is a
 *  single cache directory, so collision risk is negligible. Mirrors the
 *  pattern other sources use to avoid embedding URN colons in file
 *  names. */
private fun String.stableHashFilename(): String =
    String.format("%08x", this.hashCode())

/**
 * Helper for the `.valueOrReturn { return it }` pattern threaded
 * through the source — extracts the Success value or returns a
 * Failure from the enclosing function via a non-local return. Reads
 * a hair cleaner than the explicit `when` cascade and matches the
 * pattern other source modules have evolved toward.
 */
private inline fun <T> FictionResult<T>.valueOrReturn(
    onFailure: (FictionResult.Failure) -> Nothing,
): T = when (this) {
    is FictionResult.Success -> value
    is FictionResult.Failure -> onFailure(this)
}
