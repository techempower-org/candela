package `in`.jphe.storyvox.source.github.auth

/**
 * Static OAuth-app config for storyvox's GitHub Device Flow integration.
 *
 * Issue #91. Spec: docs/superpowers/specs/2026-05-08-github-oauth-design.md
 *
 * The Device Flow is a *public-client* flow: only `client_id` is sent to
 * GitHub, never a `client_secret`. The `client_id` ships in every APK by
 * design (no secrecy), so a hardcoded constant here is the right shape —
 * this is a **deliberate exception** to CLAUDE.md's Vaultwarden rule, which
 * applies to actual secrets.
 *
 * The OAuth app is registered to JP's account; if it ever needs to rotate
 * (e.g. compromised, or the public-client posture changes), bump the
 * constant and ship a new build.
 */
object GitHubAuthConfig {

    /**
     * Live storyvox GitHub OAuth-app client id. Registered 2026-05-09 with
     * Device Flow enabled; see class kdoc for the (no-)secret posture.
     */
    const val DEFAULT_CLIENT_ID: String = "Ov23liJEGbBN5ohx95XM"

    /**
     * Default scopes requested at sign-in. Spec § Scope minimization:
     * least-privilege default. `gist` was added (#202) so the GitHub
     * source can list and resolve the user's gists as drafts. `repo`
     * (full private-repo access) remains opt-in via the Settings
     * toggle (#203). Existing signed-in users with the older scope set
     * see a fresh Device Flow on next sign-in attempt — Sky's auth
     * substrate detects the scope mismatch and re-authorizes.
     *
     * `notifications` (#1470) lets the GitHub inbox narrator read
     * `GET /notifications`; classic OAuth tokens need it (or `repo`) for
     * that endpoint. It grants read + mark-as-read on notifications only —
     * no code access. Tokens issued before #1470 lack it: the inbox's
     * Inbox tab asks the user to sign in again, while the search-backed
     * Reviews / Mentions views keep working on the old token.
     */
    const val DEFAULT_SCOPES: String = "read:user public_repo gist notifications"

    /**
     * Scopes requested when the user has opted in to "Enable private
     * repos" in Settings (#203). The `repo` scope is a strict superset
     * of `public_repo` (full repo: read/write to private + public),
     * so flipping ON re-runs Device Flow and the new token replaces
     * the old. Flipping OFF on next sign-in downgrades back to
     * [DEFAULT_SCOPES]. `gist` parallels [DEFAULT_SCOPES] — without it,
     * enabling private repos would silently drop Gists access in
     * Browse, which is a regression rather than an intent (#234).
     * `notifications` parallels [DEFAULT_SCOPES] for the same reason (#1470;
     * `repo` already covers `/notifications`, but listing it keeps the two
     * scope sets symmetric and the intent explicit).
     */
    const val PRIVATE_REPO_SCOPES: String = "read:user repo gist notifications"

    /**
     * Resolve the scope string for a fresh Device Flow request given the
     * user's "Enable private repos" preference. Centralised so the
     * ViewModel and any tests pick the same value off one switch.
     */
    fun scopesFor(privateReposEnabled: Boolean): String =
        if (privateReposEnabled) PRIVATE_REPO_SCOPES else DEFAULT_SCOPES

    /** Device Flow endpoints live on the *website* domain, not api.github.com. */
    const val DEVICE_CODE_URL: String = "https://github.com/login/device/code"
    const val ACCESS_TOKEN_URL: String = "https://github.com/login/oauth/access_token"

    /**
     * Browser-friendly verification URL. The device-code response also
     * carries a `verification_uri_complete` that pre-fills the user's
     * code via query param; the modal prefers that when present and falls
     * back to this constant.
     */
    const val VERIFICATION_URL: String = "https://github.com/login/device"

    /**
     * Where to send users to revoke storyvox's token on github.com.
     * Local sign-out always succeeds; remote revoke requires the
     * client_secret we don't have, so we deep-link instead.
     */
    const val SETTINGS_APPLICATIONS_URL: String = "https://github.com/settings/applications"
}
