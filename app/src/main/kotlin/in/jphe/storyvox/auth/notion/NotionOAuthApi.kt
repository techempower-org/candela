package `in`.jphe.storyvox.auth.notion

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1507 — thin OkHttp client for the Notion public-integration OAuth
 * token endpoint (exchange + refresh). Closest in-tree analog is
 * [`in`.jphe.storyvox.llm.auth.AnthropicTeamsAuthApi]; the divergences:
 *
 *  - **HTTP Basic auth**, not a client_id in the body — Notion is a
 *    confidential client. `Authorization: Basic base64(client_id:secret)`.
 *  - **JSON body** `{grant_type, code, redirect_uri}` — `redirect_uri` is
 *    REQUIRED in the body because we pass it in the authorize URL.
 *  - Synchronous `execute()` wrapped in `withContext(Dispatchers.IO)` per
 *    the #585 IO-pin convention (same style as NotionApi.doRequest), rather
 *    than the enqueue/callback bridge.
 *
 * A purpose-built vanilla [OkHttpClient] (no Bearer/UA interceptors) so the
 * only auth on the wire is the Basic header the endpoint expects. [tokenUrl]
 * is `open` so a MockWebServer test can point it at a fake.
 */
@Singleton
open class NotionOAuthApi(
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

    open val tokenUrl: String = NotionOAuthConfig.TOKEN_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }
    private val mediaTypeJson = "application/json".toMediaType()

    /**
     * Exchange an authorization code for tokens. `redirectUri` must equal
     * the one used in the authorize URL. Uses the configured client
     * credentials for Basic auth.
     */
    open suspend fun exchangeCode(
        code: String,
        redirectUri: String = NotionOAuthConfig.REDIRECT_URI,
    ): NotionOAuthResult {
        val body = buildJsonObject(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
        )
        return post(body)
    }

    /**
     * Refresh an access token. Notion rotates BOTH tokens on refresh, so
     * the caller must persist the new refresh token from the result.
     * Trigger (refresh-on-401 from the source layer) is a documented
     * follow-up; this method is the ready seam.
     */
    open suspend fun refresh(refreshToken: String): NotionOAuthResult {
        val body = buildJsonObject(
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
        return post(body)
    }

    private suspend fun post(jsonBody: String): NotionOAuthResult = withContext(Dispatchers.IO) {
        val clientId = NotionOAuthConfig.clientId
        val clientSecret = NotionOAuthConfig.clientSecret
        if (clientId.isBlank() || clientSecret.isBlank()) {
            return@withContext NotionOAuthResult.Failure(
                code = "no_credentials",
                message = "Notion OAuth client credentials are not configured in this build.",
            )
        }
        val basic = Base64.encodeToString(
            "$clientId:$clientSecret".toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )
        val req = Request.Builder()
            .url(tokenUrl)
            .header("Authorization", "Basic $basic")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody(mediaTypeJson))
            .build()
        try {
            client.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                val parsed = runCatching {
                    json.decodeFromString(NotionTokenResponse.serializer(), raw)
                }.getOrNull()
                when {
                    parsed?.accessToken?.isNotBlank() == true -> NotionOAuthResult.Success(
                        accessToken = parsed.accessToken,
                        refreshToken = parsed.refreshToken,
                        workspaceName = parsed.workspaceName,
                        workspaceIcon = parsed.workspaceIcon,
                    )
                    parsed?.error != null -> NotionOAuthResult.Failure(
                        code = parsed.error,
                        message = parsed.errorDescription ?: parsed.error,
                    )
                    !resp.isSuccessful -> NotionOAuthResult.Failure(
                        code = "http_${resp.code}",
                        message = "Notion token endpoint returned HTTP ${resp.code}",
                    )
                    else -> NotionOAuthResult.Failure(
                        code = "malformed",
                        message = "Notion token response had no access_token and no error",
                    )
                }
            }
        } catch (e: IOException) {
            NotionOAuthResult.Failure(code = "network", message = e.message ?: "network error")
        }
    }

    /** Minimal object builder — the bodies are tiny + fixed-shape. */
    private fun buildJsonObject(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"$k\":\"${escapeJson(v)}\""
        }

    private fun escapeJson(s: String): String = buildString(s.length + 4) {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
}

/** Outcome of a Notion OAuth token exchange / refresh. */
sealed class NotionOAuthResult {
    data class Success(
        val accessToken: String,
        /** Notion rotates this on refresh; persist it every time. */
        val refreshToken: String?,
        val workspaceName: String?,
        val workspaceIcon: String?,
    ) : NotionOAuthResult()

    /** RFC 6749 error code (or a synthetic `http_*`/`network`/`malformed`). */
    data class Failure(val code: String, val message: String) : NotionOAuthResult()
}

@Serializable
private data class NotionTokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("workspace_name") val workspaceName: String? = null,
    @SerialName("workspace_icon") val workspaceIcon: String? = null,
    @SerialName("workspace_id") val workspaceId: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)
