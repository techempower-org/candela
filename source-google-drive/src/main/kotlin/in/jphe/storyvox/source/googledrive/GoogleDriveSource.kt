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
import `in`.jphe.storyvox.data.text.htmlToPlainText
import `in`.jphe.storyvox.source.googledrive.config.GoogleDriveConfig
import `in`.jphe.storyvox.source.googledrive.net.DriveFile
import `in`.jphe.storyvox.source.googledrive.net.GoogleDriveApi
import `in`.jphe.storyvox.source.googledrive.parse.DriveBook
import `in`.jphe.storyvox.source.googledrive.parse.DriveBookReader
import `in`.jphe.storyvox.source.googledrive.parse.DriveChapter
import `in`.jphe.storyvox.source.googledrive.parse.DriveParseException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1496 / #1677 — Google Drive as a **pick-what-to-read** fiction backend.
 *
 * ## Reading model
 * The user connects their Google account via OAuth (`:app`'s
 * `GoogleDriveOAuthManager`) and chooses files or folders in the Google
 * Picker (`docs/drive-picker.html`). Under the **`drive.file`** scope the API
 * then returns exactly what was picked — so this source lists those files:
 *
 *  - **[popular]** — the authorized library, A→Z.
 *  - **[latestUpdates]** — the same set, newest-modified first.
 *  - **[search]** — `name contains` over the authorized set.
 *
 * Each file is one fiction:
 *  - **Google Docs** are read natively via the Drive *export* API
 *    (`text/plain`) — the one thing SAF's system picker can't do (a native
 *    Doc has no downloadable bytes). One chapter.
 *  - **Text files** (`text/plain`, `text/markdown`, other `text/` types) are
 *    downloaded directly (`alt=media`). One chapter.
 *  - **EPUBs and PDFs** (#1677) are downloaded as bytes and parsed with the
 *    same parsers the local importers use (`:source-epub`'s spine walk,
 *    `:source-pdf`'s text layer + OCR fallback + heading/page-batch chapter
 *    split) via the [DriveBookReader] seam — so a Drive book gets real
 *    chapters, not one wall of text. Parsed books are memoised per
 *    `fileId@modifiedTime` so detail → chapter → next chapter doesn't
 *    re-download; an edited file re-parses.
 *
 * ## Scope decision (load-bearing)
 * `drive.file` ONLY — never `drive.readonly`. The restricted scope would
 * force Google's app-verification wall + a possible CASA assessment, a
 * disproportionate barrier for an open-source app. The accepted trade-off:
 * we see only what the user explicitly picks, not their whole Drive.
 *
 * ## Auth gate
 * There is no anonymous Drive read path. A blank token short-circuits to
 * [FictionResult.AuthRequired] so the UI routes the user to Connect. Access
 * tokens live an hour: [GoogleDriveConfig.freshAccessToken] refreshes
 * proactively near expiry, and a 401/403 triggers one
 * [GoogleDriveConfig.refreshAccessToken] + retry before giving up.
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
    description = "Google Docs, PDFs, EPUBs and text files you pick from your Drive · drive.file scope (BYO Google account)",
    sourceUrl = "https://drive.google.com",
    searchHint = "Search your authorized Google Drive files by name",
    iconName = "FolderShared",
)
@Singleton
internal class GoogleDriveSource @Inject constructor(
    private val api: GoogleDriveApi,
    private val config: GoogleDriveConfig,
    private val reader: DriveBookReader,
) : FictionSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Google Drive"

    /** Parsed EPUB/PDF memo, keyed `fileId@modifiedTime`. Tiny access-order
     *  LRU — books are big; four covers "the one playing" plus browsing. */
    private val bookCache = object : LinkedHashMap<String, DriveBook>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DriveBook>): Boolean =
            size > BOOK_CACHE_SIZE
    }
    private val bookCacheLock = Mutex()

    // ─── browse ──────────────────────────────────────────────────────────

    /** Library browse: authorized readable files, A→Z. */
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
        val term = query.term.trim()
        if (term.isEmpty()) {
            return FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))
        }
        val q = "${readableTypesClause()} and name contains '${escapeQ(term)}' and trashed = false"
        return authed { token -> api.listFiles(token, q = q, orderBy = "modifiedTime desc") }.map { list ->
            ListPage(items = list.files.mapNotNull { it.toSummary() }, page = 1, hasNext = false)
        }
    }

    /**
     * Single-page listing of the authorized, readable library. Drive
     * paginates with opaque `nextPageToken`s (not integer offsets), so — like
     * :source-hackernews' `popular()` — we surface one generous page and set
     * `hasNext = false`; a `page > 1` request returns empty so the paginator
     * terminates cleanly rather than re-fetching page 1.
     */
    private suspend fun listAuthorized(page: Int, orderBy: String): FictionResult<ListPage<FictionSummary>> {
        if (page > 1) {
            if (config.current().accessToken.isBlank()) return notConnected()
            return FictionResult.Success(ListPage(items = emptyList(), page = page, hasNext = false))
        }
        val q = "${readableTypesClause()} and trashed = false"
        return authed { token -> api.listFiles(token, q = q, orderBy = orderBy) }.map { list ->
            ListPage(items = list.files.mapNotNull { it.toSummary() }, page = 1, hasNext = false)
        }
    }

    // ─── detail + chapter ────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val fileId = parseFileId(fictionId)
            ?: return FictionResult.NotFound("Not a Google Drive fictionId: $fictionId")
        val file = when (val r = authed { api.fileMeta(it, fileId) }) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val mime = file.mimeType.orEmpty()
        if (!isReadable(mime)) return notReadable(fileId, mime)
        val title = file.name?.trim()?.ifBlank { null }?.let { displayTitle(it, mime) } ?: "Untitled"
        val baseSummary = file.toSummary() ?: FictionSummary(
            id = fictionId,
            sourceId = SOURCE_ID,
            title = title,
            author = AUTHOR,
            status = FictionStatus.COMPLETED,
            chapterCount = 1,
        )

        if (isParsedBinary(mime)) {
            val book = when (val r = book(file)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return r
            }
            return FictionResult.Success(
                FictionDetail(
                    summary = baseSummary.copy(
                        author = book.author.ifBlank { AUTHOR },
                        chapterCount = book.chapters.size,
                    ),
                    chapters = book.chapters.mapIndexed { idx, ch ->
                        ChapterInfo(
                            id = chapterIdFor(fictionId, idx),
                            sourceChapterId = "$fileId#$idx",
                            index = idx,
                            title = chapterTitle(ch, idx),
                            publishedAt = null,
                        )
                    },
                    lastUpdatedAt = null,
                ),
            )
        }

        return FictionResult.Success(
            FictionDetail(
                summary = baseSummary.copy(chapterCount = 1),
                chapters = listOf(
                    ChapterInfo(
                        id = chapterIdFor(fictionId),
                        sourceChapterId = fileId,
                        index = 0,
                        title = title,
                        publishedAt = null,
                    ),
                ),
                lastUpdatedAt = null,
            ),
        )
    }

    override suspend fun chapter(fictionId: String, chapterId: String): FictionResult<ChapterContent> {
        val fileId = parseFileId(fictionId)
            ?: return FictionResult.NotFound("Not a Google Drive fictionId: $fictionId")

        val file = when (val r = authed { api.fileMeta(it, fileId) }) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val mime = file.mimeType.orEmpty()

        if (isParsedBinary(mime)) {
            val book = when (val r = book(file)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> return r
            }
            val index = parseChapterIndex(chapterId)
            val ch = book.chapters.getOrNull(index)
                ?: return FictionResult.NotFound("Chapter $chapterId not found in Google Drive file $fileId")
            return FictionResult.Success(
                ChapterContent(
                    info = ChapterInfo(
                        id = chapterId,
                        sourceChapterId = "$fileId#$index",
                        index = index,
                        title = chapterTitle(ch, index),
                        publishedAt = null,
                    ),
                    htmlBody = plainToHtml(ch.plainBody),
                    plainBody = ch.plainBody,
                ),
            )
        }

        // Google Docs: export to plain text (SAF can't do this — a native
        // Doc has no blob bytes). Everything else text-readable: alt=media.
        val bodyResult: FictionResult<String> = when {
            mime == MIME_GOOGLE_DOC -> authed { api.exportDoc(it, fileId, MIME_TEXT_PLAIN) }
            isReadableBlob(mime) -> authed { api.downloadFile(it, fileId) }
            else -> return notReadable(fileId, mime)
        }
        val raw = when (bodyResult) {
            is FictionResult.Success -> bodyResult.value
            is FictionResult.Failure -> return bodyResult
        }
        val body = normalizeTextBody(raw, mime)
        val title = file.name?.trim()?.ifBlank { null }?.let { displayTitle(it, mime) } ?: "Untitled"
        return FictionResult.Success(
            ChapterContent(
                info = ChapterInfo(
                    id = chapterId,
                    sourceChapterId = fileId,
                    index = 0,
                    title = title,
                    publishedAt = null,
                ),
                htmlBody = plainToHtml(body),
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

    /**
     * Run [block] with a live access token: blank → [notConnected]; an auth
     * failure (401/403) triggers ONE forced refresh + retry, so an expired
     * hour-long token never bounces the user back to Connect while a refresh
     * token still works (#1677).
     */
    private suspend fun <T> authed(block: suspend (String) -> FictionResult<T>): FictionResult<T> {
        val token = config.freshAccessToken().ifBlank { null } ?: return notConnected()
        val first = block(token)
        if (first !is FictionResult.AuthRequired) return first
        val refreshed = config.refreshAccessToken()?.ifBlank { null } ?: return first
        return block(refreshed)
    }

    /** Download + parse (memoised) an EPUB/PDF Drive file. */
    private suspend fun book(file: DriveFile): FictionResult<DriveBook> {
        val key = "${file.id}@${file.modifiedTime.orEmpty()}"
        bookCacheLock.withLock { bookCache[key] }?.let { return FictionResult.Success(it) }
        val bytes = when (val r = authed { api.downloadBytes(it, file.id) }) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return r
        }
        val book = try {
            if (file.mimeType == MIME_EPUB) reader.readEpub(bytes) else reader.readPdf(file.id, bytes)
        } catch (e: DriveParseException) {
            return FictionResult.NetworkError(e.message ?: "Could not read this Google Drive file", e)
        }
        if (book.chapters.isEmpty()) {
            return FictionResult.NotFound(
                "No readable text in \"${file.name.orEmpty()}\" (a scanned PDF with no text layer?)",
            )
        }
        bookCacheLock.withLock { bookCache[key] = book }
        return FictionResult.Success(book)
    }

    private fun chapterTitle(ch: DriveChapter, index: Int): String =
        ch.title.trim().ifBlank { "Part ${index + 1}" }

    private fun <T> notConnected(): FictionResult<T> =
        FictionResult.AuthRequired("Connect Google Drive to browse your authorized files")

    private fun <T> notReadable(fileId: String, mime: String): FictionResult<T> =
        FictionResult.NotFound("Google Drive file $fileId is not a readable type (mimeType=$mime)")

    /** Drive `q` clause matching every file type this source can narrate. */
    private fun readableTypesClause(): String =
        READABLE_MIME_TYPES.joinToString(separator = " or ", prefix = "(", postfix = ")") {
            "mimeType = '$it'"
        }

    private fun DriveFile.toSummary(): FictionSummary? {
        val fileName = name?.trim().orEmpty()
        if (fileName.isBlank()) return null
        val mime = mimeType.orEmpty()
        if (mime == MIME_FOLDER || !isReadable(mime)) return null
        val descParts = mutableListOf<String>()
        kindLabel(mime)?.let { descParts += it }
        description?.trim()?.takeIf { it.isNotBlank() }?.let { descParts += it }
        return FictionSummary(
            id = fictionIdFor(id),
            sourceId = SOURCE_ID,
            title = displayTitle(fileName, mime),
            author = AUTHOR,
            description = descParts.joinToString(" · ").ifBlank { null },
            status = FictionStatus.COMPLETED,
            // EPUB/PDF chapter counts are only known after parsing.
            chapterCount = if (isParsedBinary(mime)) null else 1,
        )
    }

    internal companion object {
        const val MIME_GOOGLE_DOC = "application/vnd.google-apps.document"
        const val MIME_FOLDER = "application/vnd.google-apps.folder"
        const val MIME_TEXT_PLAIN = "text/plain"
        const val MIME_TEXT_MARKDOWN = "text/markdown"
        const val MIME_TEXT_HTML = "text/html"
        const val MIME_PDF = "application/pdf"
        const val MIME_EPUB = "application/epub+zip"

        /** Everything listed in Browse (and offered by the Picker page). */
        val READABLE_MIME_TYPES: List<String> = listOf(
            MIME_GOOGLE_DOC, MIME_PDF, MIME_EPUB, MIME_TEXT_PLAIN, MIME_TEXT_MARKDOWN,
        )

        /** Drive files have owners, but the field mask omits them; a single
         *  display author keeps the Browse rows clean. */
        const val AUTHOR = "Google Drive"

        private const val BOOK_CACHE_SIZE = 4
    }
}

/** Stable plugin id — the single source of truth (no SourceIds entry). */
internal const val SOURCE_ID = "google-drive"

/** `google-drive:<fileId>` encoding, matching the cross-source scheme. */
internal fun fictionIdFor(fileId: String): String = "$SOURCE_ID:$fileId"

/** Inverse of [fictionIdFor] — null on a malformed / foreign id. */
internal fun parseFileId(fictionId: String): String? =
    fictionId.substringAfter("$SOURCE_ID:", missingDelimiterValue = "").takeIf { it.isNotEmpty() }

/** Chapter id: `<fictionId>::c<index>` (index 0 for single-chapter files). */
internal fun chapterIdFor(fictionId: String, index: Int = 0): String = "$fictionId::c$index"

/** Inverse of [chapterIdFor]'s index; 0 for a malformed id. */
internal fun parseChapterIndex(chapterId: String): Int =
    chapterId.substringAfterLast("::c", missingDelimiterValue = "0").toIntOrNull()?.coerceAtLeast(0) ?: 0

/** True for the binary formats parsed locally into multiple chapters. */
internal fun isParsedBinary(mime: String): Boolean =
    mime == GoogleDriveSource.MIME_EPUB || mime == GoogleDriveSource.MIME_PDF

/** True for blob file types we can read directly via `alt=media`. */
internal fun isReadableBlob(mime: String): Boolean = mime.startsWith("text/")

/** True for every mime type this source narrates. */
internal fun isReadable(mime: String): Boolean =
    mime == GoogleDriveSource.MIME_GOOGLE_DOC || isParsedBinary(mime) || isReadableBlob(mime)

/** Short kind label for the Browse row description. */
internal fun kindLabel(mime: String): String? = when (mime) {
    GoogleDriveSource.MIME_GOOGLE_DOC -> "Google Doc"
    GoogleDriveSource.MIME_PDF -> "PDF"
    GoogleDriveSource.MIME_EPUB -> "EPUB"
    else -> null
}

private val FILE_EXTENSION = Regex("\\.(pdf|epub|txt|md|markdown)$", RegexOption.IGNORE_CASE)
private val PARAGRAPH_BREAK = Regex("\\n\\s*\\n")

/** Drop a redundant `.pdf`/`.epub`/`.txt`/`.md` extension from the title. */
internal fun displayTitle(fileName: String, mime: String): String {
    if (mime == GoogleDriveSource.MIME_GOOGLE_DOC) return fileName
    return fileName.replace(FILE_EXTENSION, "").ifBlank { fileName }
}

/** Docs' text/plain export leads with a UTF-8 BOM and uses CRLF; HTML files
 *  dropped in Drive read as prose, not markup. */
internal fun normalizeTextBody(raw: String, mime: String): String {
    val text = raw.removePrefix("﻿").replace("\r\n", "\n")
    return if (mime == GoogleDriveSource.MIME_TEXT_HTML) text.htmlToPlainText() else text
}

/** Plain text → paragraph HTML for the reader's htmlBody. */
internal fun plainToHtml(plain: String): String =
    plain.split(PARAGRAPH_BREAK)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>${escapeHtml(it)}</p>" }
        .ifEmpty { "<p></p>" }

/** Escape a user term for a Drive `q` single-quoted string literal. */
internal fun escapeQ(term: String): String = term.replace("\\", "\\\\").replace("'", "\\'")

/** Minimal HTML escape for the `<p>` htmlBody round-trip. */
internal fun escapeHtml(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
