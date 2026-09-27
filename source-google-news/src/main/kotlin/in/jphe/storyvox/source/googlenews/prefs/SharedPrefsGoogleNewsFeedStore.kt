package `in`.jphe.storyvox.source.googlenews.prefs

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.data.repository.GoogleNewsEdition
import `in`.jphe.storyvox.data.repository.GoogleNewsFeedStore
import `in`.jphe.storyvox.data.repository.GoogleNewsPersonalFeed
import `in`.jphe.storyvox.data.repository.GoogleNewsTopic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1678 — persistent [GoogleNewsFeedStore], owned by the source module
 * so the personalized feed needs no edit to the shared settings store
 * (and no settings↔source Dagger cycle: nothing here depends on settings).
 *
 * A private SharedPreferences file; this singleton is its only writer, so
 * an in-memory [MutableStateFlow] mirrors it and drives the live [feed].
 * The disk load happens once, lazily, on [Dispatchers.IO].
 */
@Singleton
internal class SharedPrefsGoogleNewsFeedStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : GoogleNewsFeedStore {

    private val mutex = Mutex()

    @Volatile
    private var state: MutableStateFlow<GoogleNewsPersonalFeed>? = null

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private suspend fun state(): MutableStateFlow<GoogleNewsPersonalFeed> =
        state ?: mutex.withLock {
            state ?: MutableStateFlow(withContext(Dispatchers.IO) { decode(prefs) }).also { state = it }
        }

    override val feed: Flow<GoogleNewsPersonalFeed> = flow { emitAll(state()) }

    override suspend fun current(): GoogleNewsPersonalFeed = state().value

    override suspend fun update(transform: (GoogleNewsPersonalFeed) -> GoogleNewsPersonalFeed) {
        val flow = state()
        mutex.withLock {
            val next = transform(flow.value)
            if (next == flow.value) return
            withContext(Dispatchers.IO) { encode(prefs.edit(), next).apply() }
            flow.value = next
        }
    }

    internal companion object {
        const val PREFS_NAME = "google_news_personal_feed"
        private const val K_EDITION = "edition"
        private const val K_TOPICS = "topics"
        private const val K_SEARCHES = "searches"
        private const val K_LOCATIONS = "locations"

        /** Newline-joined lists keep order (a StringSet would not);
         *  [GoogleNewsPersonalFeed.normalizeTerm] strips newlines from terms. */
        private const val SEP = "\n"

        fun decode(p: SharedPreferences): GoogleNewsPersonalFeed = GoogleNewsPersonalFeed(
            edition = GoogleNewsEdition.fromName(p.getString(K_EDITION, null)),
            topics = split(p.getString(K_TOPICS, null)).mapNotNull(GoogleNewsTopic::fromKey).distinct(),
            searches = split(p.getString(K_SEARCHES, null)),
            locations = split(p.getString(K_LOCATIONS, null)),
        )

        fun encode(e: SharedPreferences.Editor, f: GoogleNewsPersonalFeed): SharedPreferences.Editor =
            e.putString(K_EDITION, f.edition.name)
                .putString(K_TOPICS, f.topics.joinToString(SEP) { it.name })
                .putString(K_SEARCHES, f.searches.joinToString(SEP))
                .putString(K_LOCATIONS, f.locations.joinToString(SEP))

        private fun split(raw: String?): List<String> =
            raw.orEmpty().split(SEP).map { it.trim() }.filter { it.isNotEmpty() }
    }
}
