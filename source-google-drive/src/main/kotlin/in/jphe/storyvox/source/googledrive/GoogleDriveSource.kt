package `in`.jphe.storyvox.source.googledrive

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.data.source.model.map
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfig
import `in`.jphe.storyvox.source.googledrive.net.DriveFile
import `in`.jphe.storyvox.source.googledrive.net.GoogleDriveApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1496 — Google Drive as a **folder-as-library** fiction backend.
 *
 * ## Reading model
 * The user connects their Google account via OAuth (`:app`'s
 * `GoogleDriveOAuthManager`) and grants specific folders through the Google
 * Picker. Under the **`drive.file`** scope the API then returns exactly
 * those granted files — so this source lists the user's authorized
 * text-readable files as fictions:
 *
 *  - **[popular]** — the authorized library, A→Z (folder-as-library browse).
 *  - **[latestUpdates]** — the same set, newest-modified first ("newly added
 *    files in authorized folders", per the issue).
 *  - **[search]** — `name contains` over the authorized set.
 *
 * Each file is one fiction with one chapter. **Google Docs are read
 * natively** via the Drive *export* API (`text/plain`) — the one thing SAF's
 * system picker can't do (a native Doc has no downloadable bytes). Plain
 * plain-text files (`text/plain`, `text/markdown`) are downloaded directly
 * (`alt=media`). Rich binaries
 * (EPUB/PDF/ODT) are intentionally *not* surfaced here — SAF's document
 * picker already opens those Drive files into Candela's import pipeline
 * today (see docs/faq — "Does Candela read from Google Drive?").
 *
 * ## Scope decision (load-bearing)
 * `drive.file` ONLY — never `drive.readonly`. The restricted scope would
 * force Google's app-verification wall + a possible CASA assessment, a
 * disproportionate barrier for an open-source app. The accepted trade-off:
 * we see only what the user explicitly grants, not their whole Drive.
 *
 * ## Auth gate
 * There is no anonymous Drive read path. Every call reads the OAuth token
 * from [GoogleDriveConfig]; a blank token short-circuits to
 * [FictionResult.AuthRequired] so the UI routes the user to Connect.
 *
 * The `@SourcePlugin` id below is the SINGLE source of truth for this
 * backend's identity — no `SourceIds` constant (that table is frozen).
 */
@SourcePlugin(
    // Literal (not the SOURCE_ID const) so the KSP pass never depends on
    // same-file forward-const resolution; the two are kept identical.
    id = "google-drive",
    displayName = "Google Drive",
    // Connect-gated: hidden by default until the user OAuth-connects.
    defaultEnabled = false,
    category = SourceCategory.Ebook,
    supportsSearch = true,
    description = "Your authorized Drive folders · Google Docs read natively via export · drive.file scope (BYO Google account)",
    sourceUrl = "https://drive.google.com",
    searchHint = "Search your authorized Google Drive files by name",
    iconName = "FolderShared",
)
@Singleton
internal class GoogleDriveSource @Inject constructor(
    private val api: GoogleDriveApi,
    private val config: GoogleDriveConfig,
) : FictionSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Google Drive"

    // ─── browse ──────────────────────────────────────────────────────────

    /** Folder-as-library browse: authorized text-readable files, A→Z. */
    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        listAuthorized(page, orderBy = "name")

    /** "Newly added" surface: the same authorized set, newest-modified first. */
    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        listAuthorized(page, orderBy = "modifiedTime desc")

    /** Drive has no genre taxonomy — genre-less, like :source-rss. */
    override suspend fun byGenre(genre: String, page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val token = token() ?: return notConnected()
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        val q = "${readableTypesClause()} and name contains '${escapeQ(term)}' and trashed = false"
        return api.listFiles(token, q = q, orderBy = "modifiedTime desc").map { list ->
            ListPage(items = list.files.mapNotNull { it.toSummary() }, page = 1, hasNext = false)
        }
    }

    /**
     * Single-page listing of the authorized, text-readable library. Drive
     * paginates with opaque `nextPageToken`s (not integer offsets), so — like
     * :source-hackernews' `popular()` — we surface one generous page and set
     * `hasNext = false`; a `page > 1` request returns empty so the paginator
     * terminates cleanly rather than re-fetching page 1.
     */
    private suspend fun listAuthorized(page: Int, orderBy: String): FictionResult<ListPage<FictionSummary>> {
        val token = token() ?: return notConnected()
        if (page > 1) {
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val q = "${readableTypesClause()} and trashed = false"
        return api.listFiles(token, q = q, orderBy = orderBy).map { list ->
            ListPage(items = list.files.mapNotNull { it.toSummary() }, page = 1, hasNext = false)
        }
    }

    // ─── detail + chapter ────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val token = token() ?: return notConnected()
        val fileId = parseFileId(fictionId)
            ?: return FictionResult.NotFound("Not a Google Drive fictionId: $fictionId")
        return api.fileMeta(token, fileId).map { file ->
            val summary = file.toSummary() ?: FictionSummary(
                id = fictionId,
                sourceId = SOURCE_ID,
                title = file.name?.ifBlank { null } ?: "Untitled",
                author = AUTHOR,
                status = FictionStatus.COMPLETED,
                chapterCount = 1,
            )
            FictionDetail(
                summary = summary.copy(chapterCount = 1),
                chapters = listOf(
                    ChapterInfo(
                        id = chapterIdFor(fictionId),
                        sourceChapterId = fileId,
                        index = 0,
                        title = summary.title,
                        publishedAt = null,
                    ),
                ),
                lastUpdatedAt = null,
            )
        }
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val token = token() ?: return notConnected()
        val fileId = parseFileId(fictionId)
            ?: return FictionResult.NotFound("Not a Google Drive fictionId: $fictionId")

        val file = when (val r = api.fileMeta(token, fileId)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val mime = file.mimeType.orEmpty()

        // Google Docs: export to plain text (SAF can't do this — a native
        // Doc has no blob bytes). Everything else text-readable: alt=media.
        val bodyResult: FictionResult<String> = when {
            mime == MIME_GOOGLE_DOC -> api.exportDoc(token, fileId, MIME_TEXT_PLAIN)
            isReadableBlob(mime) -> api.downloadFile(token, fileId)
            else -> return FictionResult.NotFound(
                "Google Drive file $fileId is not text-readable (mimeType=$mime); " +
                    "open it via the system share/import instead",
            )
        }
        val body = when (bodyResult) {
            is FictionResult.Success -> bodyResult.value
            is FictionResult.Failure -> return bodyResult
        }
        val title = file.name?.ifBlank { null } ?: "Untitled"
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = fileId,
                    index = 0,
                    title = title,
                    publishedAt = null,
                ),
                htmlBody = "<p>${escapeHtml(body)}</p>",
                plainBody = body,
            ),
        )
    }

    // ─── follow (unsupported) ────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun setFollowed(fictionId: String, followed: Boolean): FictionResult<Unit> =
        FictionResult.Success(Unit)

    // ─── helpers ─────────────────────────────────────────────────────────

    /** Current OAuth token, or null when disconnected. */
    private suspend fun token(): String? = config.current().accessToken.ifBlank { null }

    private fun <T> notConnected(): FictionResult<T> =
        FictionResult.AuthRequired("Connect Google Drive to browse your authorized folders")

    /** Drive `q` clause matching the text-readable file types we surface. */
    private fun readableTypesClause(): String =
        "(mimeType = '$MIME_GOOGLE_DOC' or mimeType = '$MIME_TEXT_PLAIN' or mimeType = '$MIME_TEXT_MARKDOWN')"

    private fun DriveFile.toSummary(): FictionSummary? {
        val fileName = name?.trim().orEmpty()
        if (fileName.isBlank()) return null
        if (mimeType == MIME_FOLDER) return null
        val descParts = mutableListOf<String>()
        if (mimeType == MIME_GOOGLE_DOC) descParts += "Google Doc"
        description?.trim()?.takeIf { it.isNotBlank() }?.let { descParts += it }
        return FictionSummary(
            id = fictionIdFor(id),
            sourceId = SOURCE_ID,
            title = fileName,
            author = AUTHOR,
            description = descParts.joinToString(" · ").ifBlank { null },
            status = FictionStatus.COMPLETED,
            chapterCount = 1,
        )
    }

    /** True for blob file types we can read directly via `alt=media`. */
    private fun isReadableBlob(mime: String): Boolean =
        mime == MIME_TEXT_PLAIN || mime == MIME_TEXT_MARKDOWN || mime.startsWith("text/")

    internal companion object {
        const val MIME_GOOGLE_DOC = "application/vnd.google-apps.document"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_TEXT_MARKDOWN = "text/markdown"

        /** Drive files have owners, but the field mask omits them; a single
         *  display author keeps the Browse rows clean. */
        const val AUTHOR = "Google Drive"
    }
}

/** Stable plugin id — the single source of truth (no SourceIds entry). */
internal const val SOURCE_ID = "google-drive"

/** `google-drive:<fileId>` encoding, matching the cross-source scheme. */
internal fun fictionIdFor(fileId: String): String = "$SOURCE_ID:$fileId"

/** Inverse of [fictionIdFor] — null on a malformed / foreign id. */
internal fun parseFileId(fictionId: String): String? =
    fictionId.substringAfter("$SOURCE_ID:", missingDelimiterValue = "").takeIf { it.isNotEmpty() }

/** Single-chapter id (one chapter per Drive file). */
internal fun chapterIdFor(fictionId: String): String = "$fictionId::c0"

/** Escape a user term for a Drive `q` single-quoted string literal. */
internal fun escapeQ(term: String): String = term.replace("\\", "\\\\").replace("'", "\\'")

/** Minimal HTML escape for the single-`<p>` htmlBody round-trip. */
internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
