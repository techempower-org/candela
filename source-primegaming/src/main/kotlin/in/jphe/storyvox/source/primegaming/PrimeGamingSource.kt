package `in`.jphe.storyvox.source.primegaming

import `in`.jphe.storyvox.data.source.FictionSource
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
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingApi
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingEntry
import `in`.jphe.storyvox.source.primegaming.net.PrimeGamingFeed
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1494 — Amazon Prime Gaming "free games to claim" source.
 *
 * ## Honest framing (requirement #1)
 *
 * Prime Gaming games are **not universally free** — they are a perk of a paid
 * Amazon Prime subscription. This source never claims otherwise: the plugin
 * [description], the [displayName], and the collection fiction's synopsis all
 * state that claiming requires an active Prime membership. (This is precisely
 * why the flagship free-giveaway aggregator, GamerPower, deliberately excludes
 * Prime Gaming — verified on the #1494 research gate.)
 *
 * ## Reading model — one fiction, one chapter per claim
 *
 * There is a single synthetic fiction ("Prime Gaming Free Games"); each
 * currently-claimable title is a chapter carrying its name, claim window,
 * genres, and how to redeem. So [popular]/[latestUpdates]/[search] all return
 * that one collection, and its "chapters" are the live claims from the feed.
 *
 * ## Data path (research gate PASSED, #1494)
 *
 * Reads the community **LootScraper** Amazon-Prime Atom feed via [PrimeGamingApi]
 * (URL configurable — see [PrimeGamingConfig][in.jphe.storyvox.source.primegaming.config.PrimeGamingConfig]).
 * Attribution to LootScraper (MIT, github.com/eikowagenknecht/lootscraper) is
 * carried in the collection synopsis, matching the feed's own self-attribution.
 */
@SourcePlugin(
    id = "primegaming",
    displayName = "Prime Gaming",
    defaultEnabled = false,
    category = SourceCategory.Other,
    supportsSearch = true,
    description = "Amazon Prime Gaming free-game claims · requires a Prime subscription · via community LootScraper feed",
    sourceUrl = "https://gaming.amazon.com/home",
    chipLabel = "Prime",
    searchHint = "Search Prime Gaming's current free-game claims",
    iconName = "SportsEsports",
)
@Singleton
internal class PrimeGamingSource @Inject constructor(
    private val api: PrimeGamingApi,
) : FictionSource {

    override val id: String = "primegaming"
    override val displayName: String = "Prime Gaming"

    // ── browse: one collection fiction ──────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        collectionPage(page)

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        collectionPage(page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        // Only one fiction exists; genre filtering a single collection is
        // meaningless, so surface it under any genre for discoverability.
        collectionPage(page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        return api.feed().map { fetch ->
            val feed = fetch.feed
            // Match the collection when the query is blank (browse-as-search) or
            // hits "prime gaming" / any current game title. One fiction either
            // way — but an empty page for an unrelated term keeps search honest.
            val matches = term.isBlank() ||
                COLLECTION_TITLE.lowercase().contains(term) ||
                "prime gaming".contains(term) ||
                feed.entries.any { it.game.lowercase().contains(term) }
            val items = if (matches) listOf(collectionSummary(feed)) else emptyList()
            ListPage(items = items, page = 1, hasNext = false)
        }
    }

    // ── detail + chapter ────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        if (fictionId != FICTION_ID) return FictionResult.NotFound("unknown fiction $fictionId")
        return api.feed().map { fetch ->
            val feed = fetch.feed
            FictionDetail(
                summary = collectionSummary(feed),
                chapters = feed.entries.mapIndexed { index, entry -> entry.toChapterInfo(index) },
                genres = feed.entries.flatMap { it.genres }.distinct(),
                lastUpdatedAt = null,
            )
        }
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        if (fictionId != FICTION_ID) return FictionResult.NotFound("unknown fiction $fictionId")
        return when (val result = api.feed()) {
            is FictionResult.Success -> {
                val feed = result.value.feed
                val index = feed.entries.indexOfFirst { it.id == chapterId }
                if (index < 0) {
                    // The claim expired out of the feed since the list was cached.
                    FictionResult.NotFound("claim $chapterId is no longer listed")
                } else {
                    FictionResult.Success(feed.entries[index].toChapterContent(index))
                }
            }
            is FictionResult.Failure -> result
        }
    }

    // ── catalog + polling ───────────────────────────────────────────────────

    override suspend fun genres(): FictionResult<List<String>> =
        // Genre picker degrades gracefully: a feed hiccup shows no genres rather
        // than an error dialog.
        when (val result = api.feed()) {
            is FictionResult.Success ->
                FictionResult.Success(result.value.feed.entries.flatMap { it.genres }.distinct().sorted())
            is FictionResult.Failure -> FictionResult.Success(emptyList())
        }

    override suspend fun latestRevisionToken(fictionId: String): FictionResult<String?> {
        if (fictionId != FICTION_ID) return FictionResult.Success(null)
        // Cheap-poll seam: the feed's ETag/Last-Modified. The worker compares it
        // to the stored token and skips fictionDetail when unchanged; our Api
        // already speaks conditional-GET, so an unchanged feed is a cheap 304.
        return api.feed().map { it.revision }
    }

    // ── auth-gated: not applicable ──────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ── mapping ─────────────────────────────────────────────────────────────

    /** Page-1 → the one collection fiction; later pages → empty (terminates paging). */
    private suspend fun collectionPage(page: Int): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) {
            return FictionResult.Success(ListPage(emptyList(), page, hasNext = false))
        }
        return api.feed().map { fetch ->
            ListPage(items = listOf(collectionSummary(fetch.feed)), page = 1, hasNext = false)
        }
    }

    private fun collectionSummary(feed: PrimeGamingFeed): FictionSummary =
        FictionSummary(
            id = FICTION_ID,
            sourceId = id,
            title = COLLECTION_TITLE,
            author = "Amazon Prime Gaming",
            description = COLLECTION_SYNOPSIS,
            tags = listOf("Prime Gaming", "Requires Prime", "Free to claim"),
            status = FictionStatus.ONGOING,
            chapterCount = feed.entries.size,
        )

    private fun PrimeGamingEntry.toChapterInfo(index: Int): ChapterInfo =
        ChapterInfo(
            id = id,
            sourceChapterId = id,
            index = index,
            title = game.ifBlank { "Prime Gaming claim" },
        )

    private fun PrimeGamingEntry.toChapterContent(index: Int): ChapterContent {
        val plain = buildString {
            append(game.ifBlank { "This Prime Gaming title" })
            append(". Free to claim on Amazon Prime Gaming — requires an active Amazon Prime subscription.")
            if (validFrom != null || validTo != null) {
                append(" Claim window: ")
                append(validFrom ?: "now")
                append(" to ")
                append(validTo ?: "an unannounced end date")
                append(".")
            }
            if (genres.isNotEmpty()) append(" Genres: ${genres.joinToString(", ")}.")
            if (releaseDate != null) append(" Released $releaseDate.")
            if (!description.isNullOrBlank()) append(" About the game: $description")
            if (!claimUrl.isNullOrBlank()) append(" Claim it at $claimUrl")
        }
        val html = buildString {
            append("<h2>").append(game.htmlEscape()).append("</h2>")
            append("<p>Free to claim on <strong>Amazon Prime Gaming</strong> — ")
            append("requires an active Amazon Prime subscription.</p>")
            if (validFrom != null || validTo != null) {
                append("<p><strong>Claim window:</strong> ")
                append((validFrom ?: "now").htmlEscape())
                append(" &ndash; ")
                append((validTo ?: "unannounced").htmlEscape())
                append("</p>")
            }
            if (genres.isNotEmpty()) {
                append("<p><strong>Genres:</strong> ")
                append(genres.joinToString(", ").htmlEscape()).append("</p>")
            }
            if (releaseDate != null) {
                append("<p><strong>Released:</strong> ").append(releaseDate.htmlEscape()).append("</p>")
            }
            if (!description.isNullOrBlank()) {
                append("<p>").append(description.htmlEscape()).append("</p>")
            }
            if (!claimUrl.isNullOrBlank()) {
                val safe = claimUrl.htmlEscape()
                append("<p><a href=\"").append(safe).append("\">Claim on Amazon Prime</a></p>")
            }
        }
        return ChapterContent(
            info = toChapterInfo(index),
            htmlBody = html,
            plainBody = plain,
        )
    }

    private fun String.htmlEscape(): String =
        replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        /** The one synthetic fiction this source exposes. */
        private const val FICTION_ID = "primegaming:amazon"
        private const val COLLECTION_TITLE = "Prime Gaming Free Games"
        private const val COLLECTION_SYNOPSIS =
            "Amazon Prime Gaming's current free games to claim — one chapter per title, with its " +
                "claim window and how to redeem. Requires an active Amazon Prime subscription to claim; " +
                "these are a paid-Prime perk, not universally-free giveaways. Data via the community " +
                "LootScraper feed (MIT, github.com/eikowagenknecht/lootscraper)."
    }
}
