package `in`.jphe.storyvox.source.googledrive.net

import `in`.jphe.storyvox.data.source.model.FictionResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import javax.inject.Inject

/**
 * Issue #1496 — HTTP client for the Google Drive v3 REST API.
 *
 * Scope posture (load-bearing): every call is authorized with a
 * **`drive.file`** OAuth access token, so the API only ever returns files
 * the user granted to Candela through the Google OAuth flow + Picker. We
 * never request `drive.readonly` — that restricted scope triggers Google's
 * app-verification wall and a possible CASA security assessment, a
 * disproportionate barrier for an open-source app (see the issue + the PR
 * body). The trade-off: we can't enumerate the user's whole Drive, only the
 * folders/files they explicitly authorize.
 *
 * The [get] wrapper keeps the contract-kit invariants intact: it pins the
 * blocking OkHttp call to `Dispatchers.IO` (#585) and maps status codes to
 * typed [FictionResult] failures — never a raw HTTP code, body, or thrown
 * exception. The Cloudflare sniff stays ahead of the 401/403 auth mapping
 * per docs/CONTRIBUTING-SOURCES.md (Google fronts nothing with Cloudflare,
 * but the kit asserts the ordering for every source).
 *
 * [baseUrl] is `open` so the contract test retargets it at a MockWebServer
 * (mirrors `StandardEbooksApi.baseUrl` / `HackerNewsApi.firebaseBase`).
 */
internal open class GoogleDriveApi @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Test seam — `open` so unit tests point this at a MockWebServer. */
    internal open val baseUrl: String get() = BASE_URL

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * `GET /files` — list files the token is authorized for. [q] is the
     * Drive query-string filter (e.g. `'<folderId>' in parents and
     * trashed = false`, or a `mimeType` predicate). Ordered newest-first by
     * default; [pageToken] continues a prior page (null for the first).
     *
     * `spaces=drive` scopes to the user's Drive (not appDataFolder/photos).
     * The `fields` mask keeps the payload to just what the mapper needs.
     */
    suspend fun listFiles(
        accessToken: String,
        q: String,
        pageToken: String? = null,
        orderBy: String = "modifiedTime desc",
        pageSize: Int = DEFAULT_PAGE_SIZE,
    ): FictionResult<DriveFileList> {
        val params = buildList {
            add("q" to q)
            add("fields" to LIST_FIELDS)
            add("orderBy" to orderBy)
            add("pageSize" to pageSize.coerceIn(1, 1000).toString())
            add("spaces" to "drive")
            add("supportsAllDrives" to "true")
            add("includeItemsFromAllDrives" to "true")
            if (!pageToken.isNullOrBlank()) add("pageToken" to pageToken)
        }
        val query = params.joinToString("&") { (k, v) -> "$k=${enc(v)}" }
        return get("/files?$query", accessToken, "application/json") {
            json.decodeFromString(DriveFileList.serializer(), it)
        }
    }

    /** `GET /files/{id}` (metadata only) — used by fictionDetail/chapter to
     *  learn a file's name + mimeType before choosing export vs download. */
    suspend fun fileMeta(accessToken: String, fileId: String): FictionResult<DriveFile> =
        get(
            "/files/${enc(fileId)}?fields=$META_FIELDS&supportsAllDrives=true",
            accessToken,
            "application/json",
        ) { json.decodeFromString(DriveFile.serializer(), it) }

    /**
     * `GET /files/{id}/export?mimeType=…` — export a Google Workspace doc
     * (Google Doc) to a text format. This is the one thing SAF's system
     * picker can't do: a native Google Doc has no downloadable bytes, only
     * an export rendering. We ask for `text/plain` (clean TTS input) or
     * `text/html` (structure-preserving) per [mimeType].
     */
    suspend fun exportDoc(
        accessToken: String,
        fileId: String,
        mimeType: String,
    ): FictionResult<String> =
        get("/files/${enc(fileId)}/export?mimeType=${enc(mimeType)}", accessToken, mimeType) { it }

    /**
     * `GET /files/{id}?alt=media` — download a *binary/blob* file's raw
     * content (a `text/plain`, `text/markdown`, or similarly text-native
     * file the user dropped in an authorized folder). Google Docs must use
     * [exportDoc] instead — `alt=media` 403s on native Workspace types.
     */
    suspend fun downloadFile(accessToken: String, fileId: String): FictionResult<String> =
        get("/files/${enc(fileId)}?alt=media&supportsAllDrives=true", accessToken, "*/*") { it }

    /**
     * IO-pinned GET with the shared status→[FictionResult] mapping. Adds a
     * `Bearer` header only when [accessToken] is non-blank; a blank token
     * lets the server answer 401 → [FictionResult.AuthRequired] (the source
     * short-circuits before ever reaching here when disconnected, but this
     * keeps the wrapper honest either way).
     */
    private suspend fun <T> get(
        path: String,
        accessToken: String,
        accept: String,
        parse: (String) -> T,
    ): FictionResult<T> = withContext(Dispatchers.IO) {
        val url = baseUrl + path
        try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", accept)
                .get()
            if (accessToken.isNotBlank()) {
                builder.header("Authorization", "Bearer $accessToken")
            }
            client.newCall(builder.build()).execute().use { resp ->
                when {
                    resp.code == 404 -> FictionResult.NotFound("Google Drive: $path not found")
                    resp.code == 401 -> FictionResult.AuthRequired("HTTP 401 from Google Drive")
                    resp.code == 403 -> {
                        // CF sniff MUST precede the auth mapping (contract
                        // kit asserts the ordering). Google itself doesn't
                        // Cloudflare-gate, but a captive-portal/proxy might.
                        val body = resp.body?.string().orEmpty()
                        if (looksLikeCfChallenge(body)) {
                            FictionResult.NetworkError(
                                "Google Drive returned a challenge page — try again later",
                                IOException("Cloudflare challenge"),
                            )
                        } else {
                            // A plain 403 from Drive is "insufficient
                            // permission" for this token/file — an auth
                            // problem (reconnect / re-grant), not a rate
                            // limit (Drive rate-limits with 429).
                            FictionResult.AuthRequired("HTTP 403 from Google Drive")
                        }
                    }
                    resp.code == 429 -> FictionResult.RateLimited(
                        retryAfter = resp.header("Retry-After")?.toLongOrNull(),
                        message = "Google Drive rate limited (HTTP 429)",
                    )
                    !resp.isSuccessful -> FictionResult.NetworkError(
                        "HTTP ${resp.code} from Google Drive",
                        IOException("HTTP ${resp.code}"),
                    )
                    else -> {
                        val text = resp.body?.string()
                            ?: return@withContext FictionResult.NetworkError(
                                "empty body",
                                IOException("empty body"),
                            )
                        FictionResult.Success(parse(text))
                    }
                }
            }
        } catch (e: IOException) {
            FictionResult.NetworkError(e.message ?: "fetch failed", e)
        } catch (e: kotlinx.serialization.SerializationException) {
            FictionResult.NetworkError("Google Drive returned an unexpected response shape", e)
        }
    }

    private fun looksLikeCfChallenge(body: String): Boolean =
        body.contains("/cdn-cgi/challenge-platform/") ||
            body.contains("Just a moment...") ||
            body.contains("cf-mitigated")

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        /** Drive v3 REST base. Media/export ride the same host + prefix. */
        const val BASE_URL = "https://www.googleapis.com/drive/v3"

        private const val DEFAULT_PAGE_SIZE = 100

        /** Field mask for list responses — id + the metadata the mapper reads. */
        private const val LIST_FIELDS =
            "nextPageToken,files(id,name,mimeType,modifiedTime,size,description)"

        /** Field mask for a single-file metadata fetch. */
        private const val META_FIELDS = "id,name,mimeType,modifiedTime,size,description"
    }
}

// ── Wire types ──────────────────────────────────────────────────────────

/** A page of `files.list` results. */
@Serializable
internal data class DriveFileList(
    val files: List<DriveFile> = emptyList(),
    val nextPageToken: String? = null,
)

/**
 * One Drive file's metadata (the [GoogleDriveApi.LIST_FIELDS] subset).
 * `size` is a string in the Drive API (int64-as-string); native Google
 * Docs omit it entirely.
 */
@Serializable
internal data class DriveFile(
    val id: String,
    val name: String? = null,
    val mimeType: String? = null,
    val modifiedTime: String? = null,
    @SerialName("size") val sizeBytes: String? = null,
    val description: String? = null,
)
