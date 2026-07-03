package `in`.jphe.storyvox.auth.notion

import `in`.jphe.storyvox.BuildConfig
import java.net.URLEncoder

/**
 * Issue #1507 — static endpoints + client credentials for the Notion
 * public-integration OAuth flow. Mirrors the shape of
 * [`in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthConfig], but Notion is a
 * *confidential* client: the token endpoint authenticates with HTTP Basic
 * `client_id:client_secret`, so both credentials ship in the APK. That is
 * an accepted, documented risk (a distributed binary can't hold a real
 * secret) — see docs/notion-oauth-setup.md. The credentials are supplied
 * out-of-band via local.properties → BuildConfig with EMPTY defaults, so a
 * clean checkout / CI build has [isAvailable] == false and the app simply
 * hides the Connect button (the paste-token path still works).
 *
 * Contract pinned from developers.notion.com/docs/authorization
 * (fetched 2026-07-03). Do NOT bump the data-plane `Notion-Version` here —
 * that stays pinned in the source's NotionDefaults.
 */
object NotionOAuthConfig {

    /** OAuth consent screen; also doubles as the page picker. */
    const val AUTHORIZE_URL = "https://api.notion.com/v1/oauth/authorize"

    /** Token exchange + refresh endpoint (HTTP Basic auth). */
    const val TOKEN_URL = "https://api.notion.com/v1/oauth/token"

    /**
     * Custom-scheme redirect. MUST match the AndroidManifest intent-filter
     * (`candela://oauth/notion`) AND the redirect URI registered at
     * notion.so/my-integrations, byte-for-byte.
     */
    const val REDIRECT_URI = "candela://oauth/notion"

    /** OAuth client id from local.properties (empty when unconfigured). */
    val clientId: String get() = BuildConfig.NOTION_OAUTH_CLIENT_ID

    /** OAuth client secret from local.properties (empty when unconfigured). */
    val clientSecret: String get() = BuildConfig.NOTION_OAUTH_CLIENT_SECRET

    /**
     * True only when BOTH credentials are present. Gates the "Connect
     * Notion" button — with no creds the OAuth path is impossible, so the
     * UI shows only the paste-token fallback.
     */
    val isAvailable: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /**
     * Build the authorize URL the Custom Tab opens. `state` is the caller-
     * generated CSRF nonce (persisted before launch, verified on the
     * redirect). Params per the pinned Notion contract: `client_id`,
     * `redirect_uri`, `response_type=code`, `owner=user`, `state`.
     */
    fun authorizeUrl(state: String): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "owner" to "user",
            "state" to state,
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTHORIZE_URL?$query"
    }
}
