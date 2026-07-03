package `in`.jphe.storyvox.source.notion.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #233 — abstraction over the Notion source's persistent config.
 *
 * Mode-aware (issue #393):
 *   - [NotionMode.ANONYMOUS_PUBLIC] (zero-setup default) — reads
 *     public-shared Notion pages via the *unofficial*
 *     `www.notion.so/api/v3` endpoints (`loadPageChunk`,
 *     `queryCollection`, `syncRecordValuesMain`, `getPublicPageData`).
 *     Same surface react-notion-x uses. No token required. The
 *     [rootPageId] points at a public Notion page; storyvox walks its
 *     child blocks to expose Guides + Collections + sub-pages as
 *     individual fictions. Defaults to TechEmpower's root page id
 *     so fresh installs land on the TechEmpower content tree.
 *   - [NotionMode.OFFICIAL_PAT] — the original integration-token flow.
 *     Reads a single database via the *official* `api.notion.com/v1`
 *     REST endpoints. Required for private workspaces. Existing users
 *     who pasted a PAT in v0.5.23/.24 continue on this path
 *     unchanged.
 *
 * Mode selection (per [SettingsRepositoryUiImpl] / [NotionConfigImpl]):
 *   - Blank [apiToken] → [NotionMode.ANONYMOUS_PUBLIC].
 *   - Non-blank [apiToken] → [NotionMode.OFFICIAL_PAT].
 *
 * This means the user can switch modes simply by pasting (or clearing)
 * a PAT. The UI surfaces both knobs side-by-side; the default view is
 * the anonymous one with the TechEmpower root pre-populated.
 *
 * Implementation lives in :app on top of DataStore + the shared
 * `storyvox.secrets` EncryptedSharedPreferences — same pattern as
 * [`in`.jphe.storyvox.source.outline.config.OutlineConfig].
 *
 * The source module stays free of Android Preferences plumbing so the
 * leaf-source architecture (source modules don't depend on :app) holds.
 */
interface NotionConfig {
    /** Hot stream of the current config state. */
    val state: Flow<NotionConfigState>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun current(): NotionConfigState
}

/**
 * Issue #393 — read-mode selector.
 *
 * - [ANONYMOUS_PUBLIC] uses the unofficial `www.notion.so/api/v3`
 *   surface and a public-shared root page id. Zero setup: out of the
 *   box, storyvox reads TechEmpower's public Notion tree.
 * - [OFFICIAL_PAT] uses the official `api.notion.com/v1` REST API with
 *   a per-user integration token. Required for private workspaces.
 */
enum class NotionMode {
    /** Read public-shared Notion pages anonymously via the unofficial
     *  `www.notion.so/api/v3` surface. The default for fresh installs. */
    ANONYMOUS_PUBLIC,

    /** Read a private workspace database via `api.notion.com/v1` with
     *  an Internal Integration token. */
    OFFICIAL_PAT,
}

/**
 * One Notion config state. The active read path depends on [mode];
 * fields not needed by the chosen mode are tolerated (anonymous mode
 * ignores [apiToken]; PAT mode ignores [rootPageId]).
 */
data class NotionConfigState(
    /** Read-path selector. See [NotionMode]. Defaults to anonymous
     *  because the zero-setup TechEmpower experience is the new
     *  default (#393); existing PAT-holding users get
     *  [NotionMode.OFFICIAL_PAT] reactively via [apiToken] presence. */
    val mode: NotionMode = NotionMode.ANONYMOUS_PUBLIC,

    /** The Notion database id the source surfaces as the Browse catalog
     *  in [NotionMode.OFFICIAL_PAT]. Hyphenated (8-4-4-4-12) or compact
     *  (32 hex chars) — Notion accepts both. Defaults to
     *  [NotionDefaults.TECHEMPOWER_DATABASE_ID]. Ignored in anonymous
     *  mode (the root page id drives discovery instead). */
    val databaseId: String = NotionDefaults.TECHEMPOWER_DATABASE_ID,

    /** Root public page id for [NotionMode.ANONYMOUS_PUBLIC]. The source
     *  walks this page's child blocks and exposes child pages /
     *  collections as fictions. Defaults to
     *  [NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID]. Ignored in PAT mode. */
    val rootPageId: String = NotionDefaults.TECHEMPOWER_ROOT_PAGE_ID,

    /** PAT-style "Internal Integration Token" from
     *  notion.so/my-integrations. Empty enables anonymous mode. Stored
     *  encrypted; never exposed to the UI as a readable string (the
     *  UiSettings projection only carries a `tokenConfigured: Boolean`). */
    val apiToken: String = "",

    /** Official Notion REST API base URL. Defaults to api.notion.com;
     *  overridable for test infra without rewriting the source. */
    val baseUrl: String = NotionDefaults.BASE_URL,

    /** Unofficial Notion API base URL. Defaults to
     *  https://www.notion.so/api/v3; overridable for test fakes. */
    val unofficialBaseUrl: String = NotionDefaults.UNOFFICIAL_BASE_URL,

    /** Notion REST API version header — pinned in the source rather than
     *  the user config so storyvox + Notion never drift apart silently. */
    val apiVersion: String = NotionDefaults.API_VERSION,

    /** Issue #1507 — true when [apiToken] was obtained via the public-
     *  integration OAuth flow (Connect Notion) rather than a hand-pasted
     *  Internal Integration Token. Both ride the same Bearer seam, but
     *  the browse strategy differs: an OAuth grant can span *many*
     *  objects the user picked during consent, so [NotionPATSource]
     *  lists them via `POST /v1/search`; a pasted PAT keeps the original
     *  single-[databaseId] `query` path. Defaults to false so existing
     *  PAT users are unaffected. */
    val viaOAuth: Boolean = false,

    /** Issue #1507 — the Notion workspace name returned by the OAuth
     *  token exchange (`workspace_name`). Display-only ("Connected to
     *  <workspace>" in the Browse manage sheet); blank when not connected
     *  via OAuth. Not a secret. */
    val workspaceName: String = "",
) {
    /** True when the source can make API calls. In anonymous mode this
     *  is true whenever [rootPageId] is non-blank; in PAT mode it
     *  requires both [apiToken] and [databaseId]. */
    val isConfigured: Boolean
        get() = when (mode) {
            NotionMode.ANONYMOUS_PUBLIC -> rootPageId.isNotBlank()
            NotionMode.OFFICIAL_PAT -> apiToken.isNotBlank() && databaseId.isNotBlank()
        }
}
