package `in`.jphe.storyvox.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Issue #1678 — the user's *personalized* Google News feed.
 *
 * Google has no public API for the account-personalized "For you" feed
 * (the Chrome new-tab / Discover feed is signed-in, server-ranked and not
 * exported anywhere). What Google News DOES publish is unauthenticated RSS
 * for editions, topic sections, locations and searches. So "your feed" in
 * Candela is built from signals the user states explicitly — the edition
 * (language + region), followed topics, followed locations and saved
 * searches — and each becomes a section of `:source-google-news`, plus a
 * merged "For you" section.
 *
 * Lives in `:core-data` so the settings UI (`:feature`) and the source
 * (`:source-google-news`, which owns the persistent implementation) share
 * one model without a module cycle.
 */
data class GoogleNewsPersonalFeed(
    val edition: GoogleNewsEdition = GoogleNewsEdition.DEFAULT,
    /** Followed topic sections, in the order the user followed them. */
    val topics: List<GoogleNewsTopic> = emptyList(),
    /** Saved search terms (Google News search syntax passes through). */
    val searches: List<String> = emptyList(),
    /** Followed locations — a city, region or country name. */
    val locations: List<String> = emptyList(),
) {
    /** True when the user has followed anything — gates the "For you" section. */
    val isPersonalized: Boolean
        get() = topics.isNotEmpty() || searches.isNotEmpty() || locations.isNotEmpty()

    fun withTopic(topic: GoogleNewsTopic, followed: Boolean): GoogleNewsPersonalFeed =
        copy(topics = if (followed) (topics - topic) + topic else topics - topic)

    fun addSearch(term: String): GoogleNewsPersonalFeed =
        copy(searches = addTerm(searches, term))

    fun removeSearch(term: String): GoogleNewsPersonalFeed =
        copy(searches = searches.filterNot { it.equals(term, ignoreCase = true) })

    fun addLocation(place: String): GoogleNewsPersonalFeed =
        copy(locations = addTerm(locations, place))

    fun removeLocation(place: String): GoogleNewsPersonalFeed =
        copy(locations = locations.filterNot { it.equals(place, ignoreCase = true) })

    companion object {
        /** Per-list cap — each entry is one feed fetch for "For you". */
        const val MAX_ENTRIES: Int = 12

        /** Longest accepted term; Google News queries beyond this are noise. */
        const val MAX_TERM_LENGTH: Int = 100

        /**
         * Normalize a user-typed term: collapse whitespace (incl. newlines,
         * which the persistent store uses as a separator), trim, clamp length.
         * Returns null for a blank term.
         */
        fun normalizeTerm(raw: String): String? {
            val t = raw.replace(Regex("\\s+"), " ").trim().take(MAX_TERM_LENGTH).trim()
            return t.ifEmpty { null }
        }

        private fun addTerm(list: List<String>, raw: String): List<String> {
            val term = normalizeTerm(raw) ?: return list
            if (list.any { it.equals(term, ignoreCase = true) }) return list
            if (list.size >= MAX_ENTRIES) return list
            return list + term
        }
    }
}

/** The 8 Google News topic sections (`/rss/headlines/section/topic/<KEY>`). */
enum class GoogleNewsTopic {
    WORLD, NATION, BUSINESS, TECHNOLOGY, ENTERTAINMENT, SPORTS, SCIENCE, HEALTH;

    companion object {
        fun fromKey(key: String): GoogleNewsTopic? = entries.firstOrNull { it.name == key }
    }
}

/**
 * A Google News edition — the `hl` / `gl` / `ceid` triple every feed URL
 * carries. The three must agree or Google serves an inconsistent feed, so
 * they are fixed per edition rather than free-form. [language] is the
 * two-letter base language, used to localize section titles.
 */
enum class GoogleNewsEdition(
    val hl: String,
    val gl: String,
    val ceid: String,
    val language: String,
) {
    US_EN("en-US", "US", "US:en", "en"),
    US_ES("es-419", "US", "US:es-419", "es"),
    MX_ES("es-419", "MX", "MX:es-419", "es"),
    ES_ES("es", "ES", "ES:es", "es"),
    AR_ES("es-419", "AR", "AR:es-419", "es"),
    CO_ES("es-419", "CO", "CO:es-419", "es"),
    GB_EN("en-GB", "GB", "GB:en", "en"),
    CA_EN("en-CA", "CA", "CA:en", "en"),
    IN_EN("en-IN", "IN", "IN:en", "en"),
    AU_EN("en-AU", "AU", "AU:en", "en"),
    ;

    /** The query string appended to every feed URL of this edition. */
    val queryString: String get() = "hl=$hl&gl=$gl&ceid=$ceid"

    companion object {
        /** Pre-#1678 behaviour: US English. */
        val DEFAULT: GoogleNewsEdition = US_EN

        fun fromName(name: String?): GoogleNewsEdition =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Read/write seam for [GoogleNewsPersonalFeed]. Implemented (persistently)
 * by `:source-google-news`; injected by the settings screen.
 */
interface GoogleNewsFeedStore {

    /** Live feed configuration. */
    val feed: Flow<GoogleNewsPersonalFeed>

    /** Snapshot read. */
    suspend fun current(): GoogleNewsPersonalFeed

    /** Atomically apply [transform] and persist the result. */
    suspend fun update(transform: (GoogleNewsPersonalFeed) -> GoogleNewsPersonalFeed)
}

/** Non-persistent [GoogleNewsFeedStore] — for tests and previews. */
class InMemoryGoogleNewsFeedStore(
    initial: GoogleNewsPersonalFeed = GoogleNewsPersonalFeed(),
) : GoogleNewsFeedStore {
    private val state = MutableStateFlow(initial)
    override val feed: Flow<GoogleNewsPersonalFeed> = state.asStateFlow()
    override suspend fun current(): GoogleNewsPersonalFeed = state.value
    override suspend fun update(transform: (GoogleNewsPersonalFeed) -> GoogleNewsPersonalFeed) {
        state.update(transform)
    }
}
