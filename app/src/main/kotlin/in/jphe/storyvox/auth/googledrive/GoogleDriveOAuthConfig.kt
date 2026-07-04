package `in`.jphe.storyvox.auth.googledrive

import android.util.Base64
import `in`.jphe.storyvox.BuildConfig
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Issue #1496 — static endpoints + client config for the Google Drive
 * installed-app OAuth flow. Mirrors the shape of
 * [`in`.jphe.storyvox.auth.notion.NotionOAuthConfig], with two deliberate
 * divergences that make Google's model *safer* than Notion's:
 *
 *  1. **PKCE public client, no shipped secret.** Google's installed-app flow
 *     authenticates the token exchange with a PKCE `code_verifier` rather
 *     than a client secret. An Android/iOS OAuth client type carries no
 *     secret at all (["not applicable to requests from clients registered as
 *     Android, iOS, or Chrome applications"]). So [isAvailable] gates on the
 *     client id ALONE. [clientSecret] is optional — only a "Desktop app"
 *     client issues the (non-confidential) secret, and it's added to the
 *     token request only when present.
 *  2. **`drive.file` scope, never `drive.readonly`.** Load-bearing: the
 *     restricted read-everything scope triggers Google's verification wall
 *     and a possible CASA assessment — disproportionate for an open-source
 *     app. `drive.file` is non-sensitive: the app only ever sees files the
 *     user explicitly grants via consent + the Google Picker.
 *
 * The credentials are supplied out-of-band via local.properties →
 * BuildConfig with EMPTY defaults, so a clean checkout / CI build has
 * [isAvailable] == false and the app hides the Connect button.
 *
 * Contract pinned from developers.google.com/identity/protocols/oauth2/native-app
 * (fetched 2026-07-03). See docs/google-drive-setup.md.
 */
object GoogleDriveOAuthConfig {

    /** OAuth 2.0 authorization endpoint (consent screen). */
    const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"

    /** Token exchange + refresh endpoint. */
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    /**
     * Custom-scheme redirect. MUST match the AndroidManifest intent-filter
     * (`candela://oauth/googledrive`) AND the redirect URI registered in the
     * Google Cloud console, byte-for-byte. Google requires a custom scheme
     * OAuth client (iOS/Chrome type) or the reversed-client-id scheme; see
     * docs/google-drive-setup.md for the registration + fallback note.
     */
    const val REDIRECT_URI = "candela://oauth/googledrive"

    /**
     * The ONLY scope requested. Non-sensitive; grants per-file access to
     * what the user picks. NEVER add `drive` or `drive.readonly` here.
     */
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    /** OAuth client id from local.properties (empty when unconfigured). */
    val clientId: String get() = BuildConfig.GOOGLE_OAUTH_CLIENT_ID

    /** Optional client secret (blank for Android/iOS PKCE public clients). */
    val clientSecret: String get() = BuildConfig.GOOGLE_OAUTH_CLIENT_SECRET

    /**
     * True when the client id is present. Gates the "Connect Google Drive"
     * button. Unlike Notion this does NOT require a secret — PKCE carries
     * the client authentication.
     */
    val isAvailable: Boolean get() = clientId.isNotBlank()

    /**
     * Build the authorize URL the Custom Tab opens.
     *
     * @param state caller-generated CSRF nonce (persisted before launch,
     *  verified on the redirect).
     * @param codeChallenge the S256 PKCE challenge derived from a verifier
     *  that the caller persisted for the later token exchange.
     *
     * `access_type=offline` + `prompt=consent` request a refresh token so a
     * future silent-refresh slice can renew without re-consent.
     */
    fun authorizeUrl(state: String, codeChallenge: String): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to REDIRECT_URI,
            "response_type" to "code",
            "scope" to SCOPE,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "access_type" to "offline",
            "prompt" to "consent",
        )
        val query = params.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTHORIZE_URL?$query"
    }
}

/**
 * Issue #1496 — PKCE (RFC 7636) helpers for the installed-app flow. The
 * verifier is a high-entropy random string; the challenge is its
 * BASE64URL(SHA-256(verifier)) with no padding. The verifier MUST be
 * persisted across the redirect (the token exchange sends it back).
 */
object GoogleDrivePkce {

    /** A fresh 96-byte → base64url code verifier (well within the 43–128
     *  char RFC range). */
    fun newCodeVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    /** The S256 challenge for [verifier]. */
    fun challengeFor(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
