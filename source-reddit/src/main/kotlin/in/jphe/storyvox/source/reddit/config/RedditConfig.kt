package `in`.jphe.storyvox.source.reddit.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #1492 — abstraction over the Reddit source's persistent config.
 *
 * Mirrors the leaf-source BYOK pattern from
 * [`in`.jphe.storyvox.source.discord.config.DiscordConfig] and
 * `NotionConfig`: the source module declares the interface + state
 * shape; the host app (`:app`) supplies the DataStore +
 * EncryptedSharedPreferences-backed implementation
 * (`RedditConfigImpl`), bridged into the Hilt graph by `AppBindings`.
 *
 * ## Auth model — reddit "installed app" **userless OAuth** (BYOK)
 *
 * Reddit blocks Candela's honest descriptive User-Agent on the `.rss`
 * endpoints with an instant HTTP 429 (verified 2026-07-02, #1489) — a
 * deliberate policy nudging clients onto the official API. The
 * sanctioned path is OAuth2: the user creates their own reddit
 * **installed app** at reddit.com/prefs/apps (an installed app has a
 * client id and **no client secret** — see docs/reddit-setup.md), and
 * Candela mints a short-lived, read-only, *userless* bearer token via
 * the `installed_client` grant. This needs **no browser round-trip, no
 * redirect URI, and ships NO secret of any kind** — the cleanest BYOK
 * shape for a mobile client.
 *
 * The sanctioned free tier is ≈100 queries/minute per client id —
 * vastly better than the insta-429 that RSS hits.
 *
 * Subscribed-subreddit discovery (`genres()`) needs a *full user*
 * OAuth session, which the userless grant can't produce; until that
 * lands (a documented follow-up), `genres()` returns the user's
 * configured [favoriteSubreddits] or a curated default set.
 *
 * The client id is stored in `storyvox.secrets` (Tink-backed
 * EncryptedSharedPreferences) alongside the other source credentials,
 * for parity with Discord/Notion/Slack even though an installed-app
 * client id is not strictly a secret. Plaintext DataStore holds the
 * non-secret knobs (device id, post sort, comment settings, favorites).
 */
interface RedditConfig {
    /** Hot stream of the current config state. */
    val state: Flow<RedditConfigState>

    /** Synchronous snapshot for code paths that can't collect a Flow. */
    suspend fun current(): RedditConfigState
}

/**
 * One Reddit config state.
 *
 * @property clientId Installed-app client id from reddit.com/prefs/apps.
 *  Blank means the source fast-fails every call with
 *  [`in`.jphe.storyvox.data.source.model.FictionResult.AuthRequired].
 *  Stored encrypted; never surfaced to the UI as a readable string
 *  (the UiSettings projection carries only a `clientIdConfigured:
 *  Boolean`).
 * @property deviceId Stable random identifier for the `installed_client`
 *  grant. Reddit's spec asks for a unique-per-device string; we
 *  generate + persist a UUID once so tokens for one install are
 *  attributed consistently. Falls back to reddit's documented
 *  [RedditDefaults.DEFAULT_DEVICE_ID] sentinel when unset.
 * @property postSort Which listing a subreddit's chapters are drawn
 *  from — hot (default), new, or top. Realises the issue's
 *  "popular = hot posts / latestUpdates = new posts" as a per-source
 *  user preference (the browse verbs surface subreddit *communities*,
 *  because posts are chapters per the primary "subreddit = fiction,
 *  posts = chapters" contract).
 * @property appendTopComments When true, the top [topCommentCount]
 *  comments are appended to each chapter body as a narrated epilogue.
 * @property topCommentCount How many top comments to append when
 *  [appendTopComments] is on (clamped to
 *  [RedditDefaults.MIN_TOP_COMMENT_COUNT]..[RedditDefaults.MAX_TOP_COMMENT_COUNT]).
 * @property favoriteSubreddits Subreddit names surfaced by `genres()`
 *  (the genre-picker chips) until full-user-OAuth subscribed-subreddit
 *  discovery lands. Empty falls back to
 *  [RedditDefaults.DEFAULT_SUBREDDITS].
 * @property tokenBaseUrl OAuth token-mint host. Defaults to
 *  www.reddit.com; overridable for MockWebServer tests.
 * @property apiBaseUrl OAuth API host. Defaults to oauth.reddit.com;
 *  overridable for MockWebServer tests.
 */
data class RedditConfigState(
    val clientId: String = "",
    val deviceId: String = "",
    val postSort: RedditPostSort = RedditDefaults.DEFAULT_POST_SORT,
    val appendTopComments: Boolean = RedditDefaults.DEFAULT_APPEND_TOP_COMMENTS,
    val topCommentCount: Int = RedditDefaults.DEFAULT_TOP_COMMENT_COUNT,
    val favoriteSubreddits: List<String> = emptyList(),
    val tokenBaseUrl: String = RedditDefaults.TOKEN_BASE_URL,
    val apiBaseUrl: String = RedditDefaults.API_BASE_URL,
) {
    /** True when the source can mint a token — requires a client id. */
    val isConfigured: Boolean get() = clientId.isNotBlank()

    /** Comment count actually used, clamped to the documented bounds. */
    val effectiveCommentCount: Int
        get() = if (!appendTopComments) 0
        else topCommentCount.coerceIn(
            RedditDefaults.MIN_TOP_COMMENT_COUNT,
            RedditDefaults.MAX_TOP_COMMENT_COUNT,
        )
}

/** Which listing a subreddit's posts (chapters) are drawn from. */
enum class RedditPostSort(val wire: String) {
    HOT("hot"),
    NEW("new"),
    TOP("top"),
    ;

    companion object {
        /** Tolerant parse of a persisted wire value → default HOT. */
        fun fromWire(value: String?): RedditPostSort =
            entries.firstOrNull { it.wire.equals(value?.trim(), ignoreCase = true) }
                ?: RedditDefaults.DEFAULT_POST_SORT
    }
}

/** Non-user-configurable constants + defaults for the Reddit source. */
object RedditDefaults {
    /** OAuth token-mint host (installed_client grant lives here). */
    const val TOKEN_BASE_URL = "https://www.reddit.com"

    /** OAuth-authenticated API host (all read endpoints). */
    const val API_BASE_URL = "https://oauth.reddit.com"

    /** The userless installed-app grant type (a full URL, per reddit's spec). */
    const val INSTALLED_CLIENT_GRANT = "https://oauth.reddit.com/grants/installed_client"

    /** Reddit's documented "no tracking" device-id sentinel — used when
     *  the install hasn't minted a UUID yet. */
    const val DEFAULT_DEVICE_ID = "DO_NOT_TRACK_THIS_DEVICE"

    /** Renew the cached bearer this many seconds before its real expiry,
     *  so an in-flight request never races the boundary. */
    const val TOKEN_EXPIRY_MARGIN_SECONDS = 60L

    val DEFAULT_POST_SORT = RedditPostSort.HOT
    const val DEFAULT_APPEND_TOP_COMMENTS = false
    const val DEFAULT_TOP_COMMENT_COUNT = 5
    const val MIN_TOP_COMMENT_COUNT = 0
    const val MAX_TOP_COMMENT_COUNT = 25

    /** How many subreddits a discovery listing (popular/new/search) returns. */
    const val SUBREDDIT_LIST_LIMIT = 25

    /** How many posts a subreddit's chapter list is capped at (reddit's
     *  per-listing max is 100). */
    const val POST_LIST_LIMIT = 100

    /** Genre-picker chips until full-user-OAuth subscribed-subreddit
     *  discovery lands — reading-friendly, mostly text-heavy communities. */
    val DEFAULT_SUBREDDITS: List<String> = listOf(
        "books",
        "nosleep",
        "WritingPrompts",
        "HFY",
        "TrueReddit",
        "talesfromtechsupport",
        "todayilearned",
    )
}
