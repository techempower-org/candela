package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.source.SourceIds
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Issue #1238 — the fixed Google News catalog.
 *
 * Google News doesn't expose a per-article fetch-by-id endpoint, and
 * its article links are obfuscated `CBMi…` redirects that can't be
 * resolved without a fragile internal RPC (see the issue body). So the
 * storyvox "fiction" grain is the **section feed**, not the individual
 * article: each entry here is one fiction with a stable, self-describing
 * id (`googlenews:top`, `googlenews:topic:TECHNOLOGY`,
 * `googlenews:search:<q>`) backed by a fixed RSS URL.
 *
 * Because the id alone reconstructs the feed URL, these fictions rebuild
 * from the id with no persisted source URL — i.e. `googlenews` is
 * deliberately NOT in [SourceIds.idNeedsSourceUrlToRebuild]. Each feed
 * `<item>` becomes a chapter (mirrors the `:source-rss` item→chapter
 * shape).
 *
 * Locale (`hl`/`gl`/`ceid`) is pinned to en-US/US for v1; making it
 * user-configurable is a follow-up (tracked in the issue).
 */
internal object GoogleNewsSections {

    private const val BASE = "https://news.google.com/rss"

    /** UI language / country / country:lang. v1 fixed to US English. */
    private const val LOCALE_QS = "hl=en-US&gl=US&ceid=US:en"

    const val TOP_ID: String = "${SourceIds.GOOGLE_NEWS}:top"
    private const val TOPIC_PREFIX: String = "${SourceIds.GOOGLE_NEWS}:topic:"
    private const val SEARCH_PREFIX: String = "${SourceIds.GOOGLE_NEWS}:search:"

    /** One browsable Google News surface. */
    data class Section(
        val fictionId: String,
        val title: String,
        val feedUrl: String,
    )

    /**
     * The 8 canonical Google News topic sections, as
     * `feed-key to display-title`. The key is the path segment in
     * `/rss/headlines/section/topic/<KEY>`; the title is what storyvox
     * shows on the Browse card (slightly localized — `NATION` → "U.S.").
     */
    val TOPICS: List<Pair<String, String>> = listOf(
        "WORLD" to "World",
        "NATION" to "U.S.",
        "BUSINESS" to "Business",
        "TECHNOLOGY" to "Technology",
        "ENTERTAINMENT" to "Entertainment",
        "SPORTS" to "Sports",
        "SCIENCE" to "Science",
        "HEALTH" to "Health",
    )

    /** Top stories — the Browse landing's primary card. */
    val TOP: Section = Section(TOP_ID, "Top stories", "$BASE?$LOCALE_QS")

    private fun topicFeedUrl(key: String): String =
        "$BASE/headlines/section/topic/$key?$LOCALE_QS"

    private fun searchFeedUrl(encodedQuery: String): String =
        "$BASE/search?q=$encodedQuery&$LOCALE_QS"

    /** The full catalog shown on the Browse landing: Top stories first,
     *  then the 8 topic sections in canonical order. */
    fun catalog(): List<Section> =
        listOf(TOP) + TOPICS.map { (key, title) ->
            Section("$TOPIC_PREFIX$key", title, topicFeedUrl(key))
        }

    /**
     * The fiction id for a free-text search. The query is URL-encoded
     * INTO the id so the id stays free of spaces / reserved characters
     * and round-trips cleanly through persistence and the magic-link
     * router; [feedUrlFor] consumes the already-encoded form directly.
     */
    fun searchFictionId(query: String): String =
        "$SEARCH_PREFIX${URLEncoder.encode(query.trim(), "UTF-8")}"

    /**
     * Resolve a fiction id to its RSS feed URL, or null when the id
     * isn't a recognized Google News section / topic / search id.
     */
    fun feedUrlFor(fictionId: String): String? = when {
        fictionId == TOP_ID -> TOP.feedUrl
        fictionId.startsWith(TOPIC_PREFIX) -> {
            val key = fictionId.removePrefix(TOPIC_PREFIX)
            if (TOPICS.any { it.first == key }) topicFeedUrl(key) else null
        }
        fictionId.startsWith(SEARCH_PREFIX) -> {
            val encoded = fictionId.removePrefix(SEARCH_PREFIX)
            if (encoded.isBlank()) null else searchFeedUrl(encoded)
        }
        else -> null
    }

    /** Human-readable title for a section / search fiction id. */
    fun titleFor(fictionId: String): String = when {
        fictionId == TOP_ID -> "Top stories"
        fictionId.startsWith(TOPIC_PREFIX) -> {
            val key = fictionId.removePrefix(TOPIC_PREFIX)
            TOPICS.firstOrNull { it.first == key }?.second ?: "Google News"
        }
        fictionId.startsWith(SEARCH_PREFIX) -> {
            val encoded = fictionId.removePrefix(SEARCH_PREFIX)
            val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
            "Search: $decoded"
        }
        else -> "Google News"
    }

    /** True when [fictionId] is one this source owns. */
    fun isGoogleNewsId(fictionId: String): Boolean = feedUrlFor(fictionId) != null
}
