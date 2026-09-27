package `in`.jphe.storyvox.source.googlenews

import `in`.jphe.storyvox.data.repository.GoogleNewsEdition
import `in`.jphe.storyvox.data.repository.GoogleNewsPersonalFeed
import `in`.jphe.storyvox.data.repository.GoogleNewsTopic
import `in`.jphe.storyvox.data.source.SourceIds
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Issue #1238 — the Google News catalog; #1678 — personalized sections.
 *
 * Google News doesn't expose a per-article fetch-by-id endpoint, and
 * its article links are obfuscated `CBMi…` redirects that can't be
 * resolved without a fragile internal RPC (see the issue body). So the
 * storyvox "fiction" grain is the **section feed**, not the individual
 * article: each entry here is one fiction with a stable, self-describing
 * id (`googlenews:top`, `googlenews:topic:TECHNOLOGY`,
 * `googlenews:search:<q>`, `googlenews:geo:<place>`) backed by an RSS URL.
 *
 * Because the id alone reconstructs the feed URL, these fictions rebuild
 * from the id with no persisted source URL — i.e. `googlenews` is
 * deliberately NOT in [SourceIds.idNeedsSourceUrlToRebuild]. Each feed
 * `<item>` becomes a chapter (mirrors the `:source-rss` item→chapter
 * shape).
 *
 * **Edition (#1678).** The `hl`/`gl`/`ceid` locale is NOT part of the id:
 * it is the user's global [GoogleNewsEdition] choice, applied at fetch
 * time. Switching edition therefore re-localizes every section the user
 * already has in their library instead of orphaning it. The default
 * edition (US English) reproduces the pre-#1678 URLs byte-for-byte.
 *
 * **For you (#1678).** [FOR_YOU_ID] is a merged section with no single
 * feed URL: the source fans out to [personalSections] and interleaves the
 * results (see `GoogleNewsSource`).
 */
internal object GoogleNewsSections {

    private const val BASE = "https://news.google.com/rss"

    const val TOP_ID: String = "${SourceIds.GOOGLE_NEWS}:top"
    const val FOR_YOU_ID: String = "${SourceIds.GOOGLE_NEWS}:foryou"
    private const val TOPIC_PREFIX: String = "${SourceIds.GOOGLE_NEWS}:topic:"
    private const val SEARCH_PREFIX: String = "${SourceIds.GOOGLE_NEWS}:search:"
    private const val GEO_PREFIX: String = "${SourceIds.GOOGLE_NEWS}:geo:"

    /** One browsable Google News surface. */
    data class Section(
        val fictionId: String,
        val title: String,
        val feedUrl: String,
    )

    /**
     * The 8 canonical Google News topic sections, as
     * `feed-key to display-title` (US English titles). The key is the path
     * segment in `/rss/headlines/section/topic/<KEY>`.
     */
    val TOPICS: List<Pair<String, String>> =
        GoogleNewsTopic.entries.map { it.name to topicTitle(it, GoogleNewsEdition.DEFAULT) }

    /** Top stories — the Browse landing's primary card (default edition). */
    val TOP: Section get() = top(GoogleNewsEdition.DEFAULT)

    fun top(edition: GoogleNewsEdition): Section =
        Section(TOP_ID, topTitle(edition), "$BASE?${edition.queryString}")

    private fun topicFeedUrl(key: String, edition: GoogleNewsEdition): String =
        "$BASE/headlines/section/topic/$key?${edition.queryString}"

    private fun searchFeedUrl(encodedQuery: String, edition: GoogleNewsEdition): String =
        "$BASE/search?q=$encodedQuery&${edition.queryString}"

    /** `URLEncoder` is form-encoding (`+` for space), which is wrong in a
     *  path segment — swap to `%20`. A literal `+` is already `%2B`. */
    private fun geoFeedUrl(encodedPlace: String, edition: GoogleNewsEdition): String =
        "$BASE/headlines/section/geo/${encodedPlace.replace("+", "%20")}?${edition.queryString}"

    /** The unpersonalized catalog: Top stories, then the 8 topic sections. */
    fun catalog(edition: GoogleNewsEdition = GoogleNewsEdition.DEFAULT): List<Section> =
        listOf(top(edition)) + GoogleNewsTopic.entries.map { topicSection(it, edition) }

    fun topicSection(topic: GoogleNewsTopic, edition: GoogleNewsEdition): Section =
        Section(topicFictionId(topic), topicTitle(topic, edition), topicFeedUrl(topic.name, edition))

    fun topicFictionId(topic: GoogleNewsTopic): String = "$TOPIC_PREFIX${topic.name}"

    /**
     * The sections the user explicitly follows, in display order: topics,
     * then locations, then saved searches. These are what "For you" merges.
     */
    fun personalSections(feed: GoogleNewsPersonalFeed): List<Section> {
        val e = feed.edition
        return feed.topics.distinct().map { topicSection(it, e) } +
            feed.locations.map { place ->
                val id = geoFictionId(place)
                Section(id, titleFor(id, e), feedUrlFor(id, e)!!)
            } +
            feed.searches.map { term ->
                val id = searchFictionId(term)
                Section(id, titleFor(id, e), feedUrlFor(id, e)!!)
            }
    }

    /**
     * The Browse landing. Unpersonalized: exactly [catalog]. Personalized:
     * "For you" first, then Top stories, then the followed sections, then
     * the remaining (unfollowed) topic sections — so nothing is hidden,
     * the user's picks just come first.
     */
    fun landing(feed: GoogleNewsPersonalFeed): List<Section> {
        val e = feed.edition
        if (!feed.isPersonalized) return catalog(e)
        val personal = personalSections(feed)
        val followedIds = personal.map { it.fictionId }.toSet()
        val rest = GoogleNewsTopic.entries
            .map { topicSection(it, e) }
            .filterNot { it.fictionId in followedIds }
        // For you has no single feed URL; it is resolved by fan-out.
        return listOf(Section(FOR_YOU_ID, forYouTitle(e), ""), top(e)) + personal + rest
    }

    /**
     * The fiction id for a free-text search. The query is URL-encoded
     * INTO the id so the id stays free of spaces / reserved characters
     * and round-trips cleanly through persistence and the magic-link
     * router; [feedUrlFor] consumes the already-encoded form directly.
     */
    fun searchFictionId(query: String): String =
        "$SEARCH_PREFIX${URLEncoder.encode(query.trim(), "UTF-8")}"

    /** The fiction id for a followed location (encoded like [searchFictionId]). */
    fun geoFictionId(place: String): String =
        "$GEO_PREFIX${URLEncoder.encode(place.trim(), "UTF-8")}"

    /**
     * Resolve a fiction id to its RSS feed URL in [edition], or null when
     * the id isn't a recognized single-feed Google News id. [FOR_YOU_ID]
     * returns null here — it is a merge, not a feed.
     */
    fun feedUrlFor(
        fictionId: String,
        edition: GoogleNewsEdition = GoogleNewsEdition.DEFAULT,
    ): String? = when {
        fictionId == TOP_ID -> top(edition).feedUrl
        fictionId.startsWith(TOPIC_PREFIX) -> {
            val key = fictionId.removePrefix(TOPIC_PREFIX)
            if (GoogleNewsTopic.fromKey(key) != null) topicFeedUrl(key, edition) else null
        }
        fictionId.startsWith(SEARCH_PREFIX) -> {
            val encoded = fictionId.removePrefix(SEARCH_PREFIX)
            if (encoded.isBlank()) null else searchFeedUrl(encoded, edition)
        }
        fictionId.startsWith(GEO_PREFIX) -> {
            val encoded = fictionId.removePrefix(GEO_PREFIX)
            if (encoded.isBlank()) null else geoFeedUrl(encoded, edition)
        }
        else -> null
    }

    /** Human-readable title for a section id, localized to [edition]'s language. */
    fun titleFor(
        fictionId: String,
        edition: GoogleNewsEdition = GoogleNewsEdition.DEFAULT,
    ): String {
        val es = edition.language == "es"
        return when {
            fictionId == TOP_ID -> topTitle(edition)
            fictionId == FOR_YOU_ID -> forYouTitle(edition)
            fictionId.startsWith(TOPIC_PREFIX) -> {
                val topic = GoogleNewsTopic.fromKey(fictionId.removePrefix(TOPIC_PREFIX))
                topic?.let { topicTitle(it, edition) } ?: "Google News"
            }
            fictionId.startsWith(SEARCH_PREFIX) -> {
                val decoded = decode(fictionId.removePrefix(SEARCH_PREFIX))
                if (es) "Búsqueda: $decoded" else "Search: $decoded"
            }
            fictionId.startsWith(GEO_PREFIX) -> decode(fictionId.removePrefix(GEO_PREFIX))
            else -> "Google News"
        }
    }

    /** True when [fictionId] is one this source owns. */
    fun isGoogleNewsId(fictionId: String): Boolean =
        fictionId == FOR_YOU_ID || feedUrlFor(fictionId) != null

    // ─── localized titles ────────────────────────────────────────────────

    private fun decode(encoded: String): String =
        runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)

    private fun topTitle(edition: GoogleNewsEdition): String =
        if (edition.language == "es") "Noticias destacadas" else "Top stories"

    private fun forYouTitle(edition: GoogleNewsEdition): String =
        if (edition.language == "es") "Para ti" else "For you"

    /** Topic card titles. `NATION` is edition-relative (it is "the edition's
     *  country"), so only the US editions name it; others say "National". */
    fun topicTitle(topic: GoogleNewsTopic, edition: GoogleNewsEdition): String {
        val us = edition.gl == "US"
        return if (edition.language == "es") {
            when (topic) {
                GoogleNewsTopic.WORLD -> "Mundo"
                GoogleNewsTopic.NATION -> if (us) "EE. UU." else "Nacional"
                GoogleNewsTopic.BUSINESS -> "Negocios"
                GoogleNewsTopic.TECHNOLOGY -> "Tecnología"
                GoogleNewsTopic.ENTERTAINMENT -> "Entretenimiento"
                GoogleNewsTopic.SPORTS -> "Deportes"
                GoogleNewsTopic.SCIENCE -> "Ciencia"
                GoogleNewsTopic.HEALTH -> "Salud"
            }
        } else {
            when (topic) {
                GoogleNewsTopic.WORLD -> "World"
                GoogleNewsTopic.NATION -> if (us) "U.S." else "National"
                GoogleNewsTopic.BUSINESS -> "Business"
                GoogleNewsTopic.TECHNOLOGY -> "Technology"
                GoogleNewsTopic.ENTERTAINMENT -> "Entertainment"
                GoogleNewsTopic.SPORTS -> "Sports"
                GoogleNewsTopic.SCIENCE -> "Science"
                GoogleNewsTopic.HEALTH -> "Health"
            }
        }
    }
}
