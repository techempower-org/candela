package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.reddit.config.RedditConfig
import `in`.jphe.storyvox.source.reddit.config.RedditConfigState
import `in`.jphe.storyvox.source.reddit.config.RedditDefaults
import `in`.jphe.storyvox.source.reddit.config.RedditPostSort
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.redditDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_reddit")

private object RedditKeys {
    /** Stable per-install device id for the installed_client grant. */
    val DEVICE_ID = stringPreferencesKey("pref_reddit_device_id")
    /** Which post listing a subreddit's chapters come from (hot/new/top). */
    val POST_SORT = stringPreferencesKey("pref_reddit_post_sort")
    /** Whether to append top comments to each chapter body. */
    val APPEND_TOP_COMMENTS = booleanPreferencesKey("pref_reddit_append_top_comments")
    /** How many top comments to append when the toggle is on. */
    val TOP_COMMENT_COUNT = intPreferencesKey("pref_reddit_top_comment_count")
    /** Comma-separated favourite subreddits surfaced by genres(). */
    val FAVORITE_SUBREDDITS = stringPreferencesKey("pref_reddit_favorite_subreddits")
}

/** EncryptedSharedPreferences key for the Reddit installed-app client id.
 *  Lives next to the Discord / Notion / Slack tokens in `storyvox.secrets`.
 *  An installed-app client id is not strictly a secret, but it's stored
 *  encrypted for parity with the other source credentials. */
internal const val REDDIT_CLIENT_ID_PREF = "pref_source_reddit_client_id"

/** Plaintext-DataStore-persisted Reddit fields (no secrets). */
private data class RedditPersistedFields(
    val deviceId: String,
    val postSort: RedditPostSort,
    val appendTopComments: Boolean,
    val topCommentCount: Int,
    val favoriteSubreddits: List<String>,
)

/**
 * Issue #1492 — production [RedditConfig].
 *
 * Client id in EncryptedSharedPreferences (`storyvox.secrets`); the
 * non-secret knobs (device id, post sort, comment settings, favourite
 * subreddits) in plaintext DataStore. Mirrors [DiscordConfigImpl] /
 * `NotionConfigImpl` so the secrets store stays one consistent surface.
 *
 * Per the Matrix (#457) precedent, the backend ships functional via
 * KSP-driven plugin registration + the Plugin Manager toggle; the
 * client-id-entry Settings UI is a documented follow-up (there is no
 * generic BYOK config-field mechanism yet — see the wave's dx: issue).
 * The mutators below are the seam that UI will call.
 */
@Singleton
class RedditConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : RedditConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.redditDataStore, secrets)

    /** Bumped on every client-id write so [state] re-emits (SharedPreferences
     *  has no Flow of its own — same pattern as `DiscordConfigImpl`). */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<RedditConfigState> = combine(
        store.data.map { it.toFields() }.distinctUntilChanged(),
        secretsTick,
    ) { fields, _ ->
        fields.toState(clientId = readClientId())
    }.distinctUntilChanged()

    override suspend fun current(): RedditConfigState =
        store.data.first().toFields().toState(clientId = readClientId())

    // ─── mutators (the Settings-UI seam) ──────────────────────────────────

    /** Persist the installed-app client id. Blank clears it (source
     *  reverts to AuthRequired). Generates a device id on first set. */
    suspend fun setClientId(clientId: String?) {
        if (clientId.isNullOrBlank()) {
            secrets.edit().remove(REDDIT_CLIENT_ID_PREF).apply()
        } else {
            secrets.edit().putString(REDDIT_CLIENT_ID_PREF, clientId.trim()).apply()
            ensureDeviceId()
        }
        secretsTick.value = secretsTick.value + 1
    }

    /** True when a non-blank client id is stored — drives the UI's
     *  `redditClientIdConfigured: Boolean` projection. */
    fun isClientIdConfigured(): Boolean = !readClientId().isNullOrBlank()

    suspend fun setPostSort(sort: RedditPostSort) {
        store.edit { it[RedditKeys.POST_SORT] = sort.wire }
    }

    suspend fun setAppendTopComments(enabled: Boolean) {
        store.edit { it[RedditKeys.APPEND_TOP_COMMENTS] = enabled }
    }

    suspend fun setTopCommentCount(count: Int) {
        val safe = count.coerceIn(
            RedditDefaults.MIN_TOP_COMMENT_COUNT,
            RedditDefaults.MAX_TOP_COMMENT_COUNT,
        )
        store.edit { it[RedditKeys.TOP_COMMENT_COUNT] = safe }
    }

    /** Persist the favourite-subreddit list (order-preserving, comma-joined).
     *  Each entry is normalised (an `r/` prefix stripped) before storing. */
    suspend fun setFavoriteSubreddits(subreddits: List<String>) {
        val cleaned = subreddits
            .map { it.trim().removePrefix("/r/").removePrefix("r/").trim().trimEnd('/') }
            .filter { it.isNotEmpty() }
        store.edit { it[RedditKeys.FAVORITE_SUBREDDITS] = cleaned.joinToString(",") }
    }

    /** Wipe every stored Reddit field — Settings "Forget Reddit" path. */
    suspend fun clear() {
        store.edit { prefs ->
            prefs.remove(RedditKeys.DEVICE_ID)
            prefs.remove(RedditKeys.POST_SORT)
            prefs.remove(RedditKeys.APPEND_TOP_COMMENTS)
            prefs.remove(RedditKeys.TOP_COMMENT_COUNT)
            prefs.remove(RedditKeys.FAVORITE_SUBREDDITS)
        }
        secrets.edit().remove(REDDIT_CLIENT_ID_PREF).apply()
        secretsTick.value = secretsTick.value + 1
    }

    // ─── internals ────────────────────────────────────────────────────────

    private fun readClientId(): String? = secrets.getString(REDDIT_CLIENT_ID_PREF, "")

    /** Generate + persist a per-install device id if none exists yet. */
    private suspend fun ensureDeviceId() {
        val existing = store.data.first()[RedditKeys.DEVICE_ID]
        if (existing.isNullOrBlank()) {
            store.edit { it[RedditKeys.DEVICE_ID] = UUID.randomUUID().toString() }
        }
    }

    private fun Preferences.toFields(): RedditPersistedFields = RedditPersistedFields(
        deviceId = this[RedditKeys.DEVICE_ID].orEmpty(),
        postSort = RedditPostSort.fromWire(this[RedditKeys.POST_SORT]),
        appendTopComments = this[RedditKeys.APPEND_TOP_COMMENTS]
            ?: RedditDefaults.DEFAULT_APPEND_TOP_COMMENTS,
        topCommentCount = (this[RedditKeys.TOP_COMMENT_COUNT] ?: RedditDefaults.DEFAULT_TOP_COMMENT_COUNT)
            .coerceIn(RedditDefaults.MIN_TOP_COMMENT_COUNT, RedditDefaults.MAX_TOP_COMMENT_COUNT),
        favoriteSubreddits = this[RedditKeys.FAVORITE_SUBREDDITS]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList(),
    )

    private fun RedditPersistedFields.toState(clientId: String?): RedditConfigState =
        RedditConfigState(
            clientId = clientId.orEmpty(),
            deviceId = deviceId,
            postSort = postSort,
            appendTopComments = appendTopComments,
            topCommentCount = topCommentCount,
            favoriteSubreddits = favoriteSubreddits,
        )
}
