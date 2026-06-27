package `in`.jphe.storyvox.data.network

import javax.inject.Qualifier

/**
 * Issue #1141 — single source of truth for Candela's descriptive
 * User-Agent string.
 *
 * Several upstream APIs require or explicitly request an identifying
 * User-Agent that names the app + version + a contact channel:
 *
 *  - **Wikimedia** (Wikipedia + Wikisource) *requires* it — the REST /
 *    Action API 403s anonymous traffic without a descriptive UA per
 *    https://meta.wikimedia.org/wiki/User-Agent_policy.
 *  - **arXiv** asks automated clients to identify themselves
 *    (https://info.arxiv.org/help/robots.html).
 *  - **Radio Browser** asks callers to identify themselves in the UA so
 *    abusive clients can be contacted directly.
 *
 * Before #1141 each source baked its own stale `storyvox-*` string mixing
 * pre-rebrand identities (`jphein/storyvox`, `jp@jphein.com`) with
 * hand-maintained version numbers that drifted out of sync with the app's
 * real version. Centralising the pieces here — and building the final
 * header from the live build's version — means the rebrand or a version
 * bump is a one-line change and every source stays consistent.
 *
 * The header is applied per-OkHttpClient via the [UserAgentHeader]-qualified
 * `okhttp3.Interceptor` provided in `:app` (where `BuildConfig.VERSION_NAME`
 * is available); see `AppBindings.provideUserAgentInterceptor`. Keeping the
 * builder okhttp-free lets this foundational module stay dependency-light —
 * only the `:app` provider and the per-source DI modules touch OkHttp.
 */
object UserAgent {
    /** Product token — the rebranded app name. */
    const val APP_NAME: String = "Candela"

    /** Contact URL embedded in the UA so an upstream operator can reach
     *  the project. The app's public microsite. */
    const val CONTACT_URL: String = "https://candela.techempower.org"

    /** Contact email embedded in the UA — reaches the maintainers. */
    const val CONTACT_EMAIL: String = "support@techempower.org"

    /** Platform token. */
    const val PLATFORM: String = "Android"

    /**
     * Build the canonical descriptive User-Agent for a given app
     * [version], e.g. `Candela/1.1.6 (https://candela.techempower.org;
     * support@techempower.org) Android`.
     *
     * The shape — `product/version` followed by a parenthetical comment
     * carrying the contact URL + email — is the form Wikimedia's
     * User-Agent policy documents and the one arXiv / Radio Browser
     * expect for identifiable automated traffic.
     */
    fun format(version: String): String =
        "$APP_NAME/$version ($CONTACT_URL; $CONTACT_EMAIL) $PLATFORM"
}

/**
 * Qualifies the descriptive-User-Agent `okhttp3.Interceptor` provided in
 * `:app` (it needs `BuildConfig.VERSION_NAME`, which only the app module
 * carries). Source modules inject the qualified interceptor into their
 * dedicated OkHttpClient builders so every outbound request identifies the
 * app + version + contact — see issue #1141 and [UserAgent].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UserAgentHeader
