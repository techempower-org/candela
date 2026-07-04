package `in`.jphe.storyvox.auth.googledrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1496 — thin OkHttp client for the Google OAuth 2.0 token endpoint
 * (exchange + refresh). Closest in-tree analog is
 * [`in`.jphe.storyvox.auth.notion.NotionOAuthApi]; the divergences:
 *
 *  - **PKCE, form-encoded body** — `client_id` + `code_verifier` in an
 *    `application/x-www-form-urlencoded` body (Google's token endpoint
 *    format), NOT Notion's Basic-auth + JSON. No secret is required; if the
 *    build carries an optional "Desktop app" secret it's appended.
 *  - Synchronous `execute()` wrapped in `withContext(Dispatchers.IO)` per
 *    the #585 IO-pin convention.
 *
 * A vanilla [OkHttpClient] (no Bearer/UA interceptors) so only the params we
 * set reach the wire. [tokenUrl] is `open` so a MockWebServer test can point
 * it at a fake.
 */
@Singleton
open class GoogleDriveOAuthApi(
    private val client: OkHttpClient,
) {
    @Inject
    constructor() : this(
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build(),
    )

    open val tokenUrl: String = GoogleDriveOAuthConfig.TOKEN_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Exchange an authorization code for tokens. [codeVerifier] must be the
     * PKCE verifier whose challenge went out on the authorize URL;
     * [redirectUri] must equal the one used there.
     */
    open suspend fun exchangeCode(
        code: String,
        codeVerifier: String,
        redirectUri: String = GoogleDriveOAuthConfig.REDIRECT_URI,
    ): GoogleDriveOAuthResult {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", redirectUri)
            .add("client_id", GoogleDriveOAuthConfig.clientId)
            .apply {
                val secret = GoogleDriveOAuthConfig.clientSecret
                if (secret.isNotBlank()) add("client_secret", secret)
            }
            .build()
        return post(form)
    }

    /**
     * Refresh an access token. Google does NOT rotate the refresh token, so
     * the caller keeps the existing one when the response omits it. Trigger
     * (silent refresh-on-401 from the source layer) is a documented
     * follow-up; this method is the ready seam.
     */
    open suspend fun refresh(refreshToken: String): GoogleDriveOAuthResult {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", GoogleDriveOAuthConfig.clientId)
            .apply {
                val secret = GoogleDriveOAuthConfig.clientSecret
                if (secret.isNotBlank()) add("client_secret", secret)
            }
            .build()
        return post(form)
    }

    private suspend fun post(form: FormBody): GoogleDriveOAuthResult = withContext(Dispatchers.IO) {
        if (GoogleDriveOAuthConfig.clientId.isBlank()) {
            return@withContext GoogleDriveOAuthResult.Failure(
                code = "no_credentials",
                message = "Google Drive OAuth client id is not configured in this build.",
            )
        }
        val req = Request.Builder()
            .url(tokenUrl)
            .header("Accept", "application/json")
            .post(form)
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val parsed = runCatching {
                    json.decodeFromString(GoogleTokenResponse.serializer(), raw)
                }.getOrNull()
                when {
                    parsed?.accessToken?.isNotBlank() == true -> GoogleDriveOAuthResult.Success(
                        accessToken = parsed.accessToken,
                        refreshToken = parsed.refreshToken,
                        expiresInSeconds = parsed.expiresIn,
                    )
                    parsed?.error != null -> GoogleDriveOAuthResult.Failure(
                        code = parsed.error,
                        message = parsed.errorDescription ?: parsed.error,
                    )
                    !resp.isSuccessful -> GoogleDriveOAuthResult.Failure(
                        code = "http_${resp.code}",
                        message = "Google token endpoint returned HTTP ${resp.code}",
                    )
                    else -> GoogleDriveOAuthResult.Failure(
                        code = "malformed",
                        message = "Google token response had no access_token and no error",
                    )
                }
            }
        } catch (e: IOException) {
            GoogleDriveOAuthResult.Failure(code = "network", message = e.message ?: "network error")
        }
    }
}

/** Outcome of a Google OAuth token exchange / refresh. */
sealed class GoogleDriveOAuthResult {
    data class Success(
        val accessToken: String,
        /** Google returns this only on the first consent (or with
         *  `prompt=consent`); null on a plain refresh — keep the old one. */
        val refreshToken: String?,
        val expiresInSeconds: Long?,
    ) : GoogleDriveOAuthResult()

    /** RFC 6749 error code (or a synthetic `http_*`/`network`/`malformed`). */
    data class Failure(val code: String, val message: String) : GoogleDriveOAuthResult()
}

@Serializable
private data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String? = null,
    val scope: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
