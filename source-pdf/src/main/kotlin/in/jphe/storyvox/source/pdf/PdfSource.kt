package `in`.jphe.storyvox.source.pdf

import `in`.jphe.storyvox.data.source.FictionSource
import `in`.jphe.storyvox.data.source.SourceIds
import `in`.jphe.storyvox.data.source.plugin.SourceCategory
import `in`.jphe.storyvox.data.source.plugin.SourcePlugin
import `in`.jphe.storyvox.data.source.model.ChapterContent
import `in`.jphe.storyvox.data.source.model.ChapterInfo
import `in`.jphe.storyvox.data.source.model.FictionDetail
import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.data.source.model.FictionStatus
import `in`.jphe.storyvox.data.source.model.FictionSummary
import `in`.jphe.storyvox.data.source.model.ListPage
import `in`.jphe.storyvox.data.source.model.SearchQuery
import `in`.jphe.storyvox.source.pdf.config.PdfConfig
import `in`.jphe.storyvox.source.pdf.config.PdfFileEntry
import `in`.jphe.storyvox.source.pdf.parse.PdfChapter
import `in`.jphe.storyvox.source.pdf.parse.PdfChapterBuilder
import `in`.jphe.storyvox.source.pdf.parse.PdfPage
import `in`.jphe.storyvox.source.pdf.parse.PdfTextProvider
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #996 — local PDF files as a fiction backend. Direct sibling of
 * [in.jphe.storyvox.source.epub] (#235): the user picks a folder via
 * SAF, every `.pdf` there becomes one fiction, and the pages (grouped
 * into chapters by [PdfChapterBuilder]) become the chapter list.
 *
 * Pure user-content backend: zero network, zero ToS surface, zero
 * scraping. The user owns the .pdf files; storyvox just narrates what
 * is already on their device — the VDR-class accessibility surface for
 * syllabi, benefit letters, manuals, and papers.
 *
 * Text extraction is delegated to [PdfTextProvider], whose production
 * implementation lives in `:app` (it needs an Android `Context` for
 * PdfBox-Android + `PdfRenderer`). Scanned/image-only pages — which
 * carry no text layer — are routed by that implementation through the
 * pluggable OCR seam (#995); this source is agnostic to whether a
 * page's text came from the text layer or from OCR.
 *
 * Caching: the document is re-extracted on every fictionDetail /
 * chapter call. PdfBox text extraction is the expensive step; if it
 * ever becomes a concern, an in-memory map keyed by `fictionId` would
 * short-circuit the re-extract — the same shape source-epub notes for
 * its spine re-parse.
 */
@SourcePlugin(
    id = SourceIds.PDF,
    displayName = "Local PDF files",
    // Fresh-install discoverability: chip on by default. The backend
    // lists nothing until the user picks a folder, but the chip is the
    // affordance that teaches new users this surface exists. Mirrors
    // source-epub's #436 reasoning.
    defaultEnabled = true,
    category = SourceCategory.Text,
    supportsFollow = false,
    supportsSearch = false,
    description = "Read .pdf files from a folder you pick · zero-network",
    sourceUrl = "",
    generateRouting = true,
)
@Singleton
internal class PdfSource @Inject constructor(
    private val config: PdfConfig,
    private val textProvider: PdfTextProvider,
) : FictionSource {

    override val id: String = SourceIds.PDF
    override val displayName: String = "Local PDFs"

    // ─── browse ────────────────────────────────────────────────────────

    override suspend fun popular(page: Int): FictionResult<ListPage<FictionSummary>> =
        listIndexedDocuments()

    override suspend fun latestUpdates(page: Int): FictionResult<ListPage<FictionSummary>> =
        listIndexedDocuments()

    override suspend fun byGenre(
        genre: String,
        page: Int,
    ): FictionResult<ListPage<FictionSummary>> =
        // PDFs carry no standardized genre metadata. Surfacing nothing
        // is more honest than fabricating buckets (mirrors source-epub).
        FictionResult.Success(ListPage(items = emptyList(), page = 1, hasNext = false))

    override suspend fun genres(): FictionResult<List<String>> =
        FictionResult.Success(emptyList())

    override suspend fun search(query: SearchQuery): FictionResult<ListPage<FictionSummary>> {
        val term = query.term.trim().lowercase()
        if (term.isEmpty()) return listIndexedDocuments()
        val filtered = config.documents()
            .filter { it.displayName.lowercase().contains(term) }
            .map { it.toSummary() }
        return FictionResult.Success(ListPage(items = filtered, page = 1, hasNext = false))
    }

    private suspend fun listIndexedDocuments(): FictionResult<ListPage<FictionSummary>> {
        val all = config.documents().map { it.toSummary() }
        return FictionResult.Success(ListPage(items = all, page = 1, hasNext = false))
    }

    // ─── detail ────────────────────────────────────────────────────────

    override suspend fun fictionDetail(fictionId: String): FictionResult<FictionDetail> {
        val entry = config.documents().firstOrNull { it.fictionId == fictionId }
            ?: return FictionResult.NotFound("PDF not indexed: $fictionId")

        val chapters = buildChapters(entry)
            ?: return FictionResult.NetworkError("Failed to read PDF: ${entry.displayName}", null)

        val title = textProvider.title(entry.uriString)
            .ifBlank { entry.displayName.removeSuffix(".pdf").removeSuffix(".PDF") }
        val author = textProvider.author(entry.uriString)

        val chapterInfos = chapters.map { ch ->
            ChapterInfo(
                id = chapterIdFor(fictionId, ch.id),
                sourceChapterId = ch.id,
                index = ch.index,
                title = ch.title,
                wordCount = ch.plainBody.estimatedWordCount(),
            )
        }

        val summary = FictionSummary(
            id = fictionId,
            sourceId = SourceIds.PDF,
            title = title,
            author = author,
            status = FictionStatus.COMPLETED, // PDF = static file = no further pages
            chapterCount = chapterInfos.size,
        )
        return FictionResult.Success(FictionDetail(summary = summary, chapters = chapterInfos))
    }

    override suspend fun chapter(
        fictionId: String,
        chapterId: String,
    ): FictionResult<ChapterContent> {
        val entry = config.documents().firstOrNull { it.fictionId == fictionId }
            ?: return FictionResult.NotFound("PDF not indexed: $fictionId")

        val chapters = buildChapters(entry)
            ?: return FictionResult.NetworkError("Failed to read PDF: ${entry.displayName}", null)

        val chapter = chapters.firstOrNull { chapterIdFor(fictionId, it.id) == chapterId }
            ?: return FictionResult.NotFound("Chapter not in PDF: $chapterId")

        val info = ChapterInfo(
            id = chapterId,
            sourceChapterId = chapter.id,
            index = chapter.index,
            title = chapter.title,
            wordCount = chapter.plainBody.estimatedWordCount(),
        )
        return FictionResult.Success(
            ChapterContent(
                info = info,
                htmlBody = chapter.plainBody.toParagraphHtml(),
                plainBody = chapter.plainBody,
            ),
        )
    }

    // ─── auth-gated ─────────────────────────────────────────────────────

    override suspend fun followsList(page: Int): FictionResult<ListPage<FictionSummary>> =
        // No upstream "follows" for local files; the indexed list maps
        // to the user mental model "the PDFs I have."
        listIndexedDocuments()

    override suspend fun setFollowed(
        fictionId: String,
        followed: Boolean,
    ): FictionResult<Unit> = FictionResult.Success(Unit)

    // ─── helpers ────────────────────────────────────────────────────────

    /** Extract every page's text via [PdfTextProvider] and group into
     *  chapters. Returns null when the PDF can't be opened at all
     *  (pageCount == 0 with a non-trivial file is treated as a read
     *  failure). */
    private suspend fun buildChapters(entry: PdfFileEntry): List<PdfChapter>? {
        val count = textProvider.pageCount(entry.uriString)
        if (count <= 0) return null
        val pages = (0 until count).map { idx ->
            PdfPage(index = idx, text = textProvider.pageText(entry.uriString, idx).orEmpty())
        }
        return PdfChapterBuilder.build(pages)
    }
}

/** Compose a stable chapter id from the fictionId + the per-PDF chapter
 *  id (`page-<n>`). Keeps chapter ids unique across documents. */
private fun chapterIdFor(fictionId: String, localId: String): String =
    "${fictionId}::$localId"

private fun PdfFileEntry.toSummary(): FictionSummary = FictionSummary(
    id = fictionId,
    sourceId = SourceIds.PDF,
    title = displayName.removeSuffix(".pdf").removeSuffix(".PDF"),
    author = "",
    description = displayName,
    status = FictionStatus.COMPLETED,
)

/** Wrap plain text into minimal paragraph HTML for the reader's
 *  htmlBody field. Splits on blank lines into `<p>` blocks and
 *  HTML-escapes the content so stray `<`/`&` in the PDF text don't
 *  break rendering. The plainBody field carries the unescaped text for
 *  the TTS path. */
internal fun String.toParagraphHtml(): String =
    split(Regex("\\n\\s*\\n"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n") { "<p>${it.escapeHtml()}</p>" }

private fun String.escapeHtml(): String =
    replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

/** Cheap word-count heuristic for surfacing chapter length in the
 *  picker — split the plain text on whitespace. */
private fun String.estimatedWordCount(): Int {
    val plain = trim()
    if (plain.isEmpty()) return 0
    return plain.split(Regex("\\s+")).size
}
