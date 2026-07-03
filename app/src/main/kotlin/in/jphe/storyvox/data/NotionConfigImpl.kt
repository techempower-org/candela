package `in`.jphe.storyvox.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.notion.config.NotionConfig
import `in`.jphe.storyvox.source.notion.config.NotionConfigState
import `in`.jphe.storyvox.source.notion.config.NotionDefaults
import `in`.jphe.storyvox.source.notion.config.NotionMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.notionDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_notion")

private object NotionKeys {
    /** Notion database id — the database the source treats as the
     *  fiction catalog in [NotionMode.OFFICIAL_PAT]. Stored as the user
     *  enters it (trimmed); the Notion API accepts both hyphenated UUID
     *  and 32-hex forms. */
    val DATABASE_ID = stringPreferencesKey("pref_notion_database_id")

    /** Issue #393 — root page id for [NotionMode.ANONYMOUS_PUBLIC].
     *  Defaults to [NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID] when unset
     *  so fresh installs read TechEmpower's public Notion tree without
     *  configuration. Users can override to point at any public Notion
     *  page via Settings. */
    val ROOT_PAGE_ID = stringPreferencesKey("pref_notion_root_page_id")
}

/** EncryptedSharedPreferences key for the Notion integration token.
 *  Lives next to the palace + outline + RR cookie tokens in
 *  `storyvox.secrets`. The OAuth access token (#1507) is stored in this
 *  SAME slot so it rides the existing `Bearer` seam in `NotionApi`
 *  unchanged; the OAuth-vs-PAT distinction is carried by
 *  [NOTION_OAUTH_MARKER_PREF]. */
internal const val NOTION_API_TOKEN_PREF = "notion.api_token"

/** Issue #1507 — OAuth refresh token (rotates on every refresh). Kept
 *  encrypted alongside the access token so a future auto-refresh-on-401
 *  slice can renew silently without re-consent. */
internal const val NOTION_OAUTH_REFRESH_PREF = "notion.oauth_refresh_token"

/** Issue #1507 — "1" when the stored [NOTION_API_TOKEN_PREF] came from
 *  the OAuth Connect flow (vs a hand-pasted PAT). Drives
 *  [NotionConfigState.viaOAuth] → `/v1/search` granted-content browsing. */
internal const val NOTION_OAUTH_MARKER_PREF = "notion.oauth_marker"

/** Issue #1507 — the workspace name from the OAuth token response.
 *  Display-only ("Connected to <workspace>"); not a secret, but kept in
 *  the same bag so it re-emits on [secretsTick] with the rest of the
 *  OAuth session. */
internal const val NOTION_WORKSPACE_NAME_PREF = "notion.workspace_name"

/** Issue #1507 — the CSRF `state` nonce persisted just before the OAuth
 *  Custom Tab launches, verified against the redirect on return. Survives
 *  process death (the Custom Tab can evict the app). Single-use:
 *  [oauthState] reads it, the callback handler clears it. */
internal const val NOTION_OAUTH_STATE_PREF = "notion.oauth_state"

/**
 * Issue #233 — production [NotionConfig]. Database id in plaintext
 * DataStore (it's a public-ish identifier, not a secret — Notion DB
 * IDs are URL-visible to anyone who has the share link); integration
 * token in EncryptedSharedPreferences alongside the other source tokens.
 *
 * Same shape as [OutlineConfigImpl] — the parallel structure makes
 * the secrets store one consistent surface across :source-mempalace,
 * :source-outline, and :source-notion.
 *
 * Defaults to [NotionDefaults.TECHEMPOWER_DATABASE_ID] (#390) when no
 * persisted value is present — fresh installs land pointed at the
 * techempower.org content database without configuration. Existing
 * users with a stored value keep it; only the fallback changed.
 */
@Singleton
class NotionConfigImpl(
    private val store: DataStore<Preferences>,
    private val secrets: SharedPreferences,
) : NotionConfig {

    @Inject constructor(
        @ApplicationContext context: Context,
        secrets: SharedPreferences,
    ) : this(context.notionDataStore, secrets)

    /**
     * Tick bumped whenever [setApiToken] runs so the [state] flow
     * re-emits with the fresh token value. SharedPreferences doesn't
     * expose a Flow on its own — same pattern as `OutlineConfigImpl`
     * uses for the API-key leg, except we use a MutableStateFlow tick
     * so the combine below sees the change.
     */
    private val secretsTick = MutableStateFlow(0L)

    override val state: Flow<NotionConfigState> = combine(
        store.data.map { prefs ->
            prefs[NotionKeys.DATABASE_ID].orEmpty() to prefs[NotionKeys.ROOT_PAGE_ID].orEmpty()
        }.distinctUntilChanged(),
        secretsTick,
    ) { (storedDbId, storedRootId), _ ->
        val token = secrets.getString(NOTION_API_TOKEN_PREF, "") ?: ""
        // Issue #393 — mode is implicit: a non-blank token means the
        // user wants the PAT-driven workspace path; blank → anonymous
        // public reader. Same shape across `state` and `current()`.
        val mode = if (token.isNotBlank()) NotionMode.OFFICIAL_PAT else NotionMode.ANONYMOUS_PUBLIC
        NotionConfigState(
            mode = mode,
            databaseId = if (storedDbId.isBlank()) NotionDefaults.TECHEMPOWER_DATABASE_ID else storedDbId,
            rootPageId = if (storedRootId.isBlank()) NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID else storedRootId,
            apiToken = token,
            // #1507 — an OAuth token still has to be present for the marker
            // to matter; guarding on the token keeps a stale marker from a
            // cleared session from flipping the browse strategy.
            viaOAuth = token.isNotBlank() && secrets.getBoolean(NOTION_OAUTH_MARKER_PREF, false),
            workspaceName = secrets.getString(NOTION_WORKSPACE_NAME_PREF, "").orEmpty(),
        )
    }.distinctUntilChanged()

    override suspend fun current(): NotionConfigState {
        val prefs = store.data.first()
        val storedDbId = prefs[NotionKeys.DATABASE_ID].orEmpty()
        val storedRootId = prefs[NotionKeys.ROOT_PAGE_ID].orEmpty()
        val token = secrets.getString(NOTION_API_TOKEN_PREF, "") ?: ""
        val mode = if (token.isNotBlank()) NotionMode.OFFICIAL_PAT else NotionMode.ANONYMOUS_PUBLIC
        return NotionConfigState(
            mode = mode,
            databaseId = if (storedDbId.isBlank()) NotionDefaults.TECHEMPOWER_DATABASE_ID else storedDbId,
            rootPageId = if (storedRootId.isBlank()) NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID else storedRootId,
            apiToken = token,
            viaOAuth = token.isNotBlank() && secrets.getBoolean(NOTION_OAUTH_MARKER_PREF, false),
            workspaceName = secrets.getString(NOTION_WORKSPACE_NAME_PREF, "").orEmpty(),
        )
    }

    /**
     * Persist the database id. Trims whitespace. Empty input wipes
     * the stored value so the state flow falls back to the bundled
     * default — `Clear` behaviour without a separate `clearDatabaseId()`
     * method on the public interface.
     */
    suspend fun setDatabaseId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) {
            store.edit { it.remove(NotionKeys.DATABASE_ID) }
        } else {
            store.edit { it[NotionKeys.DATABASE_ID] = trimmed }
        }
    }

    /**
     * Persist the integration token. Null/blank clears the store
     * entry so the source returns AuthRequired on subsequent calls.
     * Bumps [secretsTick] so the state flow re-emits without an
     * explicit Settings re-fetch.
     */
    fun setApiToken(token: String?) {
        // #1507 — a hand-pasted token is a PAT, never an OAuth grant, so
        // this path also tears down any prior OAuth session state (marker,
        // refresh token, workspace name). Otherwise a user who OAuth-
        // connected then later pasted a PAT would keep viaOAuth=true and
        // wrongly browse via /v1/search instead of their configured DB.
        val editor = secrets.edit()
        if (token.isNullOrBlank()) {
            editor.remove(NOTION_API_TOKEN_PREF)
        } else {
            editor.putString(NOTION_API_TOKEN_PREF, token.trim())
        }
        editor.remove(NOTION_OAUTH_MARKER_PREF)
        editor.remove(NOTION_OAUTH_REFRESH_PREF)
        editor.remove(NOTION_WORKSPACE_NAME_PREF)
        editor.apply()
        secretsTick.value = secretsTick.value + 1
    }

    /**
     * Issue #1507 — persist a completed OAuth session: the access token
     * lands in [NOTION_API_TOKEN_PREF] (so the existing Bearer seam works
     * unchanged), plus the refresh token, the OAuth marker (→ `/v1/search`
     * granted browsing), and the workspace name for display. Bumps
     * [secretsTick] so `state` re-emits with `viaOAuth = true`.
     */
    fun saveOAuthSession(accessToken: String, refreshToken: String?, workspaceName: String?) {
        secrets.edit()
            .putString(NOTION_API_TOKEN_PREF, accessToken.trim())
            .apply {
                if (refreshToken.isNullOrBlank()) {
                    remove(NOTION_OAUTH_REFRESH_PREF)
                } else {
                    putString(NOTION_OAUTH_REFRESH_PREF, refreshToken.trim())
                }
                putString(NOTION_WORKSPACE_NAME_PREF, workspaceName.orEmpty())
            }
            .putBoolean(NOTION_OAUTH_MARKER_PREF, true)
            .apply()
        secretsTick.value = secretsTick.value + 1
    }

    /**
     * Issue #1507 — rotate the OAuth token pair after a silent refresh.
     * Keeps the marker + workspace name; only swaps the access token (and
     * the refresh token, which Notion rotates on every refresh). No-op on
     * the marker so an in-flight session stays OAuth-flavoured.
     */
    fun updateOAuthTokens(accessToken: String, refreshToken: String?) {
        secrets.edit()
            .putString(NOTION_API_TOKEN_PREF, accessToken.trim())
            .apply {
                if (!refreshToken.isNullOrBlank()) {
                    putString(NOTION_OAUTH_REFRESH_PREF, refreshToken.trim())
                }
            }
            .apply()
        secretsTick.value = secretsTick.value + 1
    }

    /** Issue #1507 — the stored OAuth refresh token, or null if none. */
    fun oauthRefreshToken(): String? =
        secrets.getString(NOTION_OAUTH_REFRESH_PREF, null)?.ifBlank { null }

    /** Issue #1507 — persist the CSRF `state` nonce before launching the
     *  OAuth Custom Tab. Survives process death. */
    fun setOAuthState(state: String) {
        secrets.edit().putString(NOTION_OAUTH_STATE_PREF, state).apply()
    }

    /** Issue #1507 — the persisted CSRF `state` nonce, or null if none. */
    fun oauthState(): String? =
        secrets.getString(NOTION_OAUTH_STATE_PREF, null)?.ifBlank { null }

    /** Issue #1507 — drop the CSRF `state` nonce once the redirect is
     *  handled (single-use). */
    fun clearOAuthState() {
        secrets.edit().remove(NOTION_OAUTH_STATE_PREF).apply()
    }

    /** True when a non-empty token is stored. Drives the UI's
     *  `notionTokenConfigured: Boolean` projection. */
    fun isTokenConfigured(): Boolean =
        !secrets.getString(NOTION_API_TOKEN_PREF, "").isNullOrBlank()

    /**
     * Persist the anonymous-mode root page id. Trims whitespace. Empty
     * input wipes the stored value so the state flow falls back to
     * [NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID]. Accepts hyphenated or
     * compact 32-hex forms — the unofficial API client hyphenates
     * before each call.
     */
    suspend fun setRootPageId(id: String) {
        val trimmed = id.trim()
        if (trimmed.isBlank()) {
            store.edit { it.remove(NotionKeys.ROOT_PAGE_ID) }
        } else {
            store.edit { it[NotionKeys.ROOT_PAGE_ID] = trimmed }
        }
    }

    /** Wipe database id, root page id, and token — Settings "Forget
     *  Notion" path (no UI affordance yet; available for diagnostics +
     *  tests). After this call the source falls back to the bundled
     *  TechEmpower defaults in anonymous mode. */
    suspend fun clear() {
        store.edit {
            it.remove(NotionKeys.DATABASE_ID)
            it.remove(NotionKeys.ROOT_PAGE_ID)
        }
        secrets.edit()
            .remove(NOTION_API_TOKEN_PREF)
            // #1507 — tear down the full OAuth session too.
            .remove(NOTION_OAUTH_REFRESH_PREF)
            .remove(NOTION_OAUTH_MARKER_PREF)
            .remove(NOTION_WORKSPACE_NAME_PREF)
            .remove(NOTION_OAUTH_STATE_PREF)
            .apply()
        secretsTick.value = secretsTick.value + 1
    }
}
