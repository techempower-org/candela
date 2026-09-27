package `in`.jphe.storyvox.source.localhelp

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
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.localhelp.net.LocalHelpApi
import `in`.jphe.storyvox.source.localhelp.net.Two11Resource
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1465 — **Local Help**: a browsable, read-aloud directory of local
 * social services (food, housing, health care …) from the **211 National Data
 * Platform** (United Way Worldwide). It extends the "Call 211" tap: dialing
 * 211 dead-ends at a phone tree, while this lets a low-literacy / low-vision
 * listener hear nearby services one by one.
 *
 * ## Shape
 * - One **fiction** = one service at one location (`localhelp:<idServiceAtLocation>`),
 *   with exactly one chapter (`…::info`) whose body is [Two11Narration.script].
 * - **Popular** = food help near the device (no location → the API geocodes the
 *   caller's IP, so "near me" needs no location permission).
 * - **Genres** = the everyday need categories in [CATEGORIES]; `byGenre` searches
 *   the matching keyword near the device.
 * - **Filters**: ZIP / city, need category, distance, sort (relevance / nearest).
 *   They travel through [SearchQuery.tags] with `loc:` / `cat:` / `mi:` /
 *   `sort:` prefixes because the universal query has no location slot (same
 *   stash-on-tags idiom as Gutenberg's category filter).
 *
 * ## Why 211 NDP and not findhelp.org
 * findhelp's API is partner-contract only; the 211 NDP is the one US-wide
 * directory with a self-serve developer key. Its Trial key is dev/test-only and
 * production use needs each contributing 211's permission — see
 * docs/localhelp-211-setup.md. Until a key is compiled in, the source is OFF by
 * default and every call returns a typed "not configured" failure.
 */
@SourcePlugin(
    id = "localhelp",
    displayName = "Local Help (211)",
    defaultEnabled = false,
    category = SourceCategory.Other,
    supportsSearch = true,
    description = "Food, housing, health & more near you · read aloud from the 211 directory · needs a 211 API key",
    sourceUrl = "https://apiportal.211.org",
    chipLabel = "Local Help",
    searchHint = "Search local services — food, shelter, rent help, clinics",
    iconName = "Explore",
)
@Singleton
internal class LocalHelpSource @Inject constructor(
    private val api: LocalHelpApi,
) : FictionSource {

    override val id: String = ID
    override val displayName: String = "Local Help (211)"

    /** Search-result records by fiction id, so detail/chapter can narrate the
     *  basics even when the v2 detail call is unavailable for a record. */
    private val seen = ConcurrentHashMap<String, Two11Resource>()

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        list(keyword = POPULAR_KEYWORD, location = null, miles = null, nearest = false, page = page)

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        popular(page)

    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        list(keyword = keywordFor(genre) ?: genre, location = null, miles = null, nearest = false, page = page)

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val p = parseTags(query.tags)
        val categoryKeyword = p.category?.let { keywordFor(it) }
        val keyword = listOfNotNull(query.term.trim().takeIf { it.isNotEmpty() }, categoryKeyword)
            .joinToString(" ")
            .ifBlank { if (p.location != null) POPULAR_KEYWORD else "" }
        // A bare blank search (no term, no filter) has nothing to ask for.
        if (keyword.isBlank()) return FictionResult.Success(ListPage(emptyList(), query.page, hasNext = false))
        return list(keyword, p.location, p.miles, p.nearest, query.page)
    }

    private suspend fun list(
        keyword: String,
        location: String?,
        miles: Int?,
        nearest: Boolean,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> {
        val safePage = page.coerceAtLeast(1)
        val skip = (safePage - 1) * PAGE_SIZE
        return api.keywordSearch(keyword, location, miles, nearest, skip, PAGE_SIZE).map { resp ->
            val items = resp.results.mapNotNull { r -> r.document?.let { doc -> toSummary(doc) } }
            val total = resp.count ?: 0L
            ListPage(
                items = items,
                page = safePage,
                hasNext = items.isNotEmpty() && skip + resp.results.size < total,
            )
        }
    }

    private fun toSummary(doc: Two11Resource): FictionSummary? {
        val localId = doc.idServiceAtLocation?.takeIf { it.isNotBlank() }
            ?: doc.id?.takeIf { it.isNotBlank() }
            ?: return null
        val fictionId = "$ID:$localId"
        seen[fictionId] = doc
        val facts = Two11Narration.factsFrom(doc)
        val title = facts.serviceName.ifBlank { facts.organization }.ifBlank { "Local service" }
        val where = listOfNotNull(
            doc.cityPhysicalAddress?.takeIf { it.isNotBlank() },
            doc.statePhysicalAddress?.takeIf { it.isNotBlank() },
        ).joinToString(", ")
        return FictionSummary(
            id = fictionId,
            sourceId = ID,
            title = title,
            author = facts.organization.ifBlank { where.ifBlank { "211" } },
            description = facts.description.take(MAX_BLURB).ifBlank { null },
            tags = (doc.topicTaxonomy + doc.subtopicTaxonomy).filter { it.isNotBlank() }.distinct().take(6),
            status = FictionStatus.COMPLETED,
            chapterCount = 1,
        )
    }

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val localId = localIdOf(fictionId) ?: return FictionResult.NotFound("Not a Local Help id: $fictionId")
        val cached = seen[fictionId]
        val summary = cached?.let(::toSummary)
            ?: when (val r = facts(fictionId, localId)) {
                is FictionResult.Success -> FictionSummary(
                    id = fictionId,
                    sourceId = ID,
                    title = r.value.serviceName.ifBlank { r.value.organization }.ifBlank { "Local service" },
                    author = r.value.organization.ifBlank { "211" },
                    description = r.value.description.take(MAX_BLURB).ifBlank { null },
                    status = FictionStatus.COMPLETED,
                    chapterCount = 1,
                )
                is FictionResult.Failure -> return r
            }
        return FictionResult.Success(
            FictionDetail(
                summary = summary,
                chapters = listOf(
                    ChapterInfo(
                        id = chapterIdFor(fictionId),
                        sourceChapterId = localId,
                        index = 0,
                        title = "About this service",
                    ),
                ),
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val localId = localIdOf(fictionId) ?: return FictionResult.NotFound("Not a Local Help id: $fictionId")
        return facts(fictionId, localId).map { f ->
            val paragraphs = Two11Narration.script(f)
            ChapterContent(
                info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = localId,
                    index = 0,
                    title = "About this service",
                ),
                htmlBody = paragraphs.joinToString("") { "<p>${escapeHtml(it)}</p>" },
                plainBody = paragraphs.joinToString("\n\n"),
            )
        }
    }

    /** Detail facts: the v2 detail call, backfilled by the cached search hit.
     *  A failed detail call still narrates when the search hit is cached. */
    private suspend fun facts(fictionId: String, localId: String): FictionResult<Two11Narration.Facts> {
        val fallback = Two11Narration.factsFrom(seen[fictionId])
        return when (val r = api.serviceAtLocation(localId)) {
            is FictionResult.Success -> FictionResult.Success(Two11Narration.factsFrom(r.value, fallback))
            is FictionResult.Failure ->
                if (seen.containsKey(fictionId)) FictionResult.Success(fallback) else r
        }
    }

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(emptyList(), page, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(CATEGORIES.map { it.first })

    override fun filterDimensions(): List<FilterDimension> = listOf(
        FilterDimension.Text(
            key = KEY_LOCATION,
            label = "ZIP code or city",
            placeholder = "e.g. 95959 or Grass Valley, CA",
        ),
        FilterDimension.Select(
            key = KEY_CATEGORY,
            label = "What do you need?",
            options = CATEGORIES.map { it.first },
        ),
        FilterDimension.Select(
            key = KEY_DISTANCE,
            label = "Distance",
            options = DISTANCES.map { "$it miles" },
        ),
        FilterDimension.Sort(
            options = listOf(
                FilterDimension.SortOption("relevance", "Best match"),
                FilterDimension.SortOption("distance", "Nearest first"),
            ),
        ),
    )

    override fun applyFilters(base: SearchQuery, state: FilterState): SearchQuery {
        val tags = base.tags.toMutableSet()
        state.stringVal(KEY_LOCATION)?.trim()?.takeIf { it.isNotEmpty() }?.let { tags += "$TAG_LOC$it" }
        state.stringVal(KEY_CATEGORY)?.takeIf { it.isNotBlank() }?.let { tags += "$TAG_CAT$it" }
        state.stringVal(KEY_DISTANCE)?.let { d -> d.filter(Char::isDigit).toIntOrNull()?.let { tags += "$TAG_MI$it" } }
        if (state.stringVal("sort") == "distance") tags += "${TAG_SORT}distance"
        return base.copy(tags = tags)
    }

    internal data class ParsedTags(
        val location: String? = null,
        val category: String? = null,
        val miles: Int? = null,
        val nearest: Boolean = false,
    )

    companion object {
        const val ID = "localhelp"
        private const val PAGE_SIZE = 10
        private const val MAX_BLURB = 280
        private const val POPULAR_KEYWORD = "food"

        internal const val KEY_LOCATION = "location"
        internal const val KEY_CATEGORY = "category"
        internal const val KEY_DISTANCE = "distance"
        private const val TAG_LOC = "loc:"
        private const val TAG_CAT = "cat:"
        private const val TAG_MI = "mi:"
        private const val TAG_SORT = "sort:"

        private val DISTANCES = listOf(5, 10, 25, 50)

        /** Everyday need categories (label → 211 keyword). Order = how a
         *  211 call-taker typically triages: food and shelter first. */
        internal val CATEGORIES: List<Pair<String, String>> = listOf(
            "Food" to "food",
            "Housing & shelter" to "shelter housing",
            "Rent & utility help" to "rent utility assistance",
            "Health care" to "health clinic",
            "Mental health" to "mental health counseling",
            "Substance use" to "substance use treatment",
            "Transportation" to "transportation",
            "Jobs & training" to "employment job training",
            "Child care & family" to "child care family",
            "Older adults" to "senior services",
            "Veterans" to "veterans",
            "Legal help" to "legal aid",
            "Crisis & safety" to "crisis domestic violence",
        )

        internal fun keywordFor(label: String): String? =
            CATEGORIES.firstOrNull { it.first.equals(label, ignoreCase = true) }?.second

        internal fun parseTags(tags: Set<String>): ParsedTags {
            var p = ParsedTags()
            for (t in tags) {
                when {
                    t.startsWith(TAG_LOC) -> p = p.copy(location = t.removePrefix(TAG_LOC).trim().ifBlank { null })
                    t.startsWith(TAG_CAT) -> p = p.copy(category = t.removePrefix(TAG_CAT))
                    t.startsWith(TAG_MI) -> p = p.copy(miles = t.removePrefix(TAG_MI).toIntOrNull())
                    t == "${TAG_SORT}distance" -> p = p.copy(nearest = true)
                }
            }
            return p
        }

        internal fun localIdOf(fictionId: String): String? =
            fictionId.substringAfter("$ID:", missingDelimiterValue = "").takeIf { it.isNotEmpty() }

        internal fun chapterIdFor(fictionId: String): String = "$fictionId::info"

        internal fun escapeHtml(s: String): String =
            s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    }
}
