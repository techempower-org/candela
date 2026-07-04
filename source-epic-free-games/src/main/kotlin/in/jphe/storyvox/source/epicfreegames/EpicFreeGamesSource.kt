package `in`.jphe.storyvox.source.epicfreegames

import `in`.jphe.storyvox.data.source.FictionSource
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
import `in`.jphe.storyvox.source.epicfreegames.net.EpicFreeGamesApi
import `in`.jphe.storyvox.source.epicfreegames.net.OfferGroup
import `in`.jphe.storyvox.source.epicfreegames.net.PromotionalOffer
import `in`.jphe.storyvox.source.epicfreegames.net.PromotionsResponse
import `in`.jphe.storyvox.source.epicfreegames.net.StoreElement
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1493 — Epic Games Store weekly free-game giveaways as a listenable
 * source.
 *
 * **Shape.** Exactly one fiction — "Epic Free Games" — whose chapters are the
 * individual giveaways. Each currently-free game and each announced upcoming
 * freebie is one chapter reading the title, the free-claim window, the normal
 * price, the publisher, and the store blurb. Current giveaways sort ahead of
 * upcoming ones; [latestUpdates] surfaces the same fiction (the "current
 * rotation" the Morning Briefing (#1467) can narrate).
 *
 * That single-fiction shape mirrors how the content is actually consumed —
 * "what's free this week" is one short episode, not a library of separate
 * books. There is no per-game detail page to drill into; every surface reads
 * from the one [EpicFreeGamesApi.fetchPromotions] document.
 *
 * **No auth, no follows, no search.** The feed is public and anonymous; there
 * is no account concept to follow against, and searching a single rotating
 * fiction has no meaning — [search] returns empty and `supportsSearch` is
 * false so Browse hides the search tab.
 *
 * **Fragile vendor JSON.** The upstream endpoint is unofficial; parsing is
 * lenient (see [EpicFreeGamesApi]) and the giveaway filter ([giveaways])
 * tolerates the feed bundling plain sales alongside the actual freebies.
 */
@SourcePlugin(
    // String literal (the scaffold convention) — the annotation id is the
    // single source of truth for this backend's identity.
    id = "epic-free-games",
    displayName = "Epic Free Games",
    defaultEnabled = false,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Epic Games Store weekly free-game giveaways · public feed, no sign-in",
    sourceUrl = "https://store.epicgames.com/free-games",
    chipLabel = "Epic",
    iconName = "Redeem",
)
@Singleton
internal class EpicFreeGamesSource @Inject constructor(
    private val api: EpicFreeGamesApi,
) : FictionSource {

    override val id: String = EPIC_SOURCE_ID
    override val displayName: String = "Epic Free Games"

    // ─── browse ────────────────────────────────────────────────────────

    /** The single "Epic Free Games" fiction, its description summarising the
     *  current rotation. Always issues the network fetch (the count in the
     *  subtitle is live). */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        singleFictionPage()

    /** Same single fiction — "latest" for this source IS the current rotation. */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        singleFictionPage()

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    /** No meaningful search over a single rotating fiction; the annotation's
     *  `supportsSearch = false` hides the Browse search tab. Returning an empty
     *  page keeps the contract kit's blank-query check honest. */
    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    private suspend fun singleFictionPage(): FictionResult<ListPage<FictionSummary>> {
        val resp = when (val r = api.fetchPromotions()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val summary = summaryOf(giveaways(resp))
        return FictionResult.Success(
            ListPage(items = listOf(summary), page = 1, hasNext = false),
        )
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        if (fictionId != EPIC_FICTION_ID) {
            return FictionResult.NotFound("Not an Epic Free Games fiction: $fictionId")
        }
        val resp = when (val r = api.fetchPromotions()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val list = giveaways(resp)
        val chapters = list.mapIndexed { i, g -> g.toChapterInfo(i) }
        return FictionResult.Success(
            FictionDetail(
                summary = summaryOf(list).copy(chapterCount = list.size),
                chapters = chapters,
            ),
        )
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        if (fictionId != EPIC_FICTION_ID) {
            return FictionResult.NotFound("Not an Epic Free Games fiction: $fictionId")
        }
        val resp = when (val r = api.fetchPromotions()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val indexed = giveaways(resp).withIndex()
            .firstOrNull { chapterIdFor(it.value.key) == chapterId }
            ?: return FictionResult.NotFound("No Epic giveaway for chapter $chapterId")
        val giveaway = indexed.value
        val body = narrateGiveaway(giveaway)
        return FictionResult.Success(
            ChapterContent(
                info = giveaway.toChapterInfo(indexed.index),
                htmlBody = body.toHtmlParagraphs(),
                plainBody = body,
            ),
        )
    }

    // ─── auth-gated (no-ops — the feed is anonymous) ─────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── polling ─────────────────────────────────────────────────────────

    /**
     * Cheap rotation-change token for the poll worker (Morning Briefing #1467).
     * Derived from the currently-free giveaways + their end dates, so the token
     * changes exactly when the weekly rotation flips. Falls back to `null` on a
     * fetch failure so the worker retries the full path.
     */
    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        if (fictionId != EPIC_FICTION_ID) return FictionResult.Success(null)
        val resp = when (val r = api.fetchPromotions()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val token = giveaways(resp).filter { it.current }
            .joinToString("|") { "${it.key}@${it.endDate}" }
            .ifBlank { "none" }
        return FictionResult.Success(token)
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun summaryOf(list: List<Giveaway>): FictionSummary {
        val currentCount = list.count { it.current }
        val upcomingCount = list.size - currentCount
        val subtitle = buildString {
            if (currentCount > 0) append("$currentCount free to claim now")
            else append("No free games right now")
            if (upcomingCount > 0) append(" · $upcomingCount coming soon")
        }
        return FictionSummary(
            id = EPIC_FICTION_ID,
            sourceId = EPIC_SOURCE_ID,
            title = "Epic Free Games",
            author = "Epic Games Store",
            description = subtitle,
            coverUrl = list.firstOrNull { it.current }?.coverUrl ?: list.firstOrNull()?.coverUrl,
            status = FictionStatus.ONGOING,
            chapterCount = list.size,
        )
    }

    private fun Giveaway.toChapterInfo(index: Int): ChapterInfo = ChapterInfo(
        id = chapterIdFor(key),
        sourceChapterId = key,
        index = index,
        title = if (current) title else "$title (coming soon)",
    )
}

// ── identity ────────────────────────────────────────────────────────────

/** @SourcePlugin id — the single source of truth for this backend (no SourceIds entry). */
internal const val EPIC_SOURCE_ID: String = "epic-free-games"

/** The one fiction this source exposes. Distinct from the source id so a routed
 *  `fictionId` is unambiguous at the repository layer. */
internal const val EPIC_FICTION_ID: String = "epic-free-games:weekly"

/** Stable chapter id: fiction id + the game's Epic element key. */
internal fun chapterIdFor(key: String): String = "$EPIC_FICTION_ID::$key"

// ── domain model + pure mappers (unit-tested without a network client) ────

/**
 * A single free-game promotion projected out of the raw feed. `current` is the
 * headline "free right now" state; `false` is an announced upcoming freebie.
 */
internal data class Giveaway(
    val key: String,
    val title: String,
    val description: String,
    val seller: String?,
    val storeUrl: String?,
    val coverUrl: String?,
    val current: Boolean,
    val startDate: String?,
    val endDate: String?,
    val originalPriceFmt: String?,
)

/**
 * Project the feed into giveaways. An element qualifies only if it carries a
 * promotional offer with `discountPercentage == 0` (pay 0% → free); the feed's
 * plain sales (20/50/80% off) are dropped. Current giveaways sort ahead of
 * upcoming ones; feed order is preserved within each group (stable sort).
 */
internal fun giveaways(resp: PromotionsResponse): List<Giveaway> {
    val out = mutableListOf<Giveaway>()
    for (el in resp.data.catalog.searchStore.elements) {
        val title = el.title?.trim().orEmpty()
        if (title.isBlank()) continue
        val promos = el.promotions ?: continue
        val currentOffer = promos.promotionalOffers.firstFreeOffer()
        val upcomingOffer = promos.upcomingPromotionalOffers.firstFreeOffer()
        val (isCurrent, offer) = when {
            currentOffer != null -> true to currentOffer
            upcomingOffer != null -> false to upcomingOffer
            else -> continue // plain sale / not a giveaway
        }
        out += Giveaway(
            // #1525 — a present-but-blank id/namespace is non-null, so a bare
            // Elvis chain would keep the "" and never fall through. Guard each
            // link with isNotBlank so the fallback reaches a usable value.
            key = el.id?.takeIf { it.isNotBlank() }
                ?: el.namespace?.takeIf { it.isNotBlank() }
                ?: title,
            title = title,
            description = el.description?.trim().orEmpty(),
            seller = el.seller?.name?.trim()?.takeIf { it.isNotBlank() },
            storeUrl = storeUrlFor(el),
            coverUrl = coverUrlFor(el),
            current = isCurrent,
            startDate = offer.startDate,
            endDate = offer.endDate,
            originalPriceFmt = el.price?.totalPrice?.fmtPrice?.originalPrice
                ?.takeIf { it.isNotBlank() && it != "0" },
        )
    }
    return out.sortedBy { if (it.current) 0 else 1 }
}

/** The first offer in these groups that makes the game free (pay 0%). Upcoming
 *  groups can bundle several offers (a free week among later % sales) — we pick
 *  the free one, not the first one. */
private fun List<OfferGroup>.firstFreeOffer(): PromotionalOffer? =
    this.asSequence()
        .flatMap { it.promotionalOffers.asSequence() }
        .firstOrNull { it.discountSetting?.discountPercentage == 0 }

/**
 * Build a store URL from whichever slug the element exposes. `productSlug` is
 * the clean canonical path; the offer/catalog `pageSlug`s are the hashed
 * current-storefront paths and both redirect. Returns null when the element
 * carries no usable slug.
 */
internal fun storeUrlFor(el: StoreElement): String? {
    // #1525 — a present-but-blank pageSlug is non-null, so a bare Elvis chain
    // would keep the "" and emit a broken ".../p/" URL instead of falling
    // through. Guard every pageSlug with isNotBlank so the chain reaches a
    // usable slug (or null).
    val slug = el.productSlug?.substringBefore('/')?.takeIf { it.isNotBlank() }
        ?: el.offerMappings.firstOrNull { it.pageType == "productHome" }?.pageSlug?.takeIf { it.isNotBlank() }
        ?: el.offerMappings.firstOrNull()?.pageSlug?.takeIf { it.isNotBlank() }
        ?: el.catalogNs?.mappings?.firstOrNull { it.pageType == "productHome" }?.pageSlug?.takeIf { it.isNotBlank() }
        ?: el.catalogNs?.mappings?.firstOrNull()?.pageSlug?.takeIf { it.isNotBlank() }
        ?: return null
    return "https://store.epicgames.com/p/$slug"
}

private fun coverUrlFor(el: StoreElement): String? {
    val imgs = el.keyImages
    return (
        imgs.firstOrNull { it.type == "OfferImageWide" }
            ?: imgs.firstOrNull { it.type == "Thumbnail" }
            ?: imgs.firstOrNull()
        )?.url?.takeIf { it.isNotBlank() }
}

/** Narratable body for one giveaway chapter. Plain text — the playback layer
 *  normalises prosody on top; the reader view gets [toHtmlParagraphs]. */
internal fun narrateGiveaway(g: Giveaway): String {
    val sb = StringBuilder()
    sb.append(g.title).append("\n\n")
    val window = formatWindow(g.startDate, g.endDate)
    sb.append(if (g.current) "Free to claim now on the Epic Games Store" else "Free to claim soon on the Epic Games Store")
    if (window != null) sb.append(", ").append(window)
    sb.append(".")
    g.originalPriceFmt?.let { sb.append(" Normally ").append(it).append(".") }
    g.seller?.let { sb.append(" Published by ").append(it).append(".") }
    if (g.description.isNotBlank()) sb.append("\n\n").append(g.description)
    g.storeUrl?.let { sb.append("\n\nClaim it at ").append(it) }
    return sb.toString().trim()
}

/** "from Jul 2, 2026 to Jul 9, 2026" / "through Jul 9, 2026" / null. */
internal fun formatWindow(startIso: String?, endIso: String?): String? {
    val start = formatEpicDate(startIso)
    val end = formatEpicDate(endIso)
    return when {
        start != null && end != null -> "from $start to $end"
        end != null -> "through $end"
        start != null -> "starting $start"
        else -> null
    }
}

private val EPIC_DATE_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US).withZone(ZoneOffset.UTC)

/** Parse Epic's ISO-8601 instant (`2026-07-02T15:00:00.000Z`) to `Jul 2, 2026`
 *  (UTC — the window is announced in UTC, and UTC keeps this deterministic).
 *  Falls back to the raw string if the shape ever changes. */
internal fun formatEpicDate(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return try {
        EPIC_DATE_FORMAT.format(Instant.parse(iso))
    } catch (_: DateTimeParseException) {
        iso
    }
}

/** Split the plain body on blank lines into `<p>` blocks for the reader view.
 *  #1525 — normalise CRLF / lone-CR line endings to `\n` and collapse runs of
 *  3+ newlines to a single blank-line separator first, so a body that arrives
 *  with `\r\n` (or extra blank lines) still splits into paragraphs instead of
 *  collapsing into one `<p>`. */
internal fun String.toHtmlParagraphs(): String =
    replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("\n{3,}"), "\n\n")
        .split("\n\n")
        .filter { it.isNotBlank() }
        .joinToString("") { "<p>${escapeHtml(it).replace("\n", "<br/>")}</p>" }

internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
