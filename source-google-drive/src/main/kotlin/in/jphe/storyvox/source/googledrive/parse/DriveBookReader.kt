package `in`.jphe.storyvox.source.googledrive.parse

import `in`.jphe.storyvox.data.text.htmlToPlainText
import `in`.jphe.storyvox.source.epub.parse.EpubParser
import `in`.jphe.storyvox.source.googledrive.di.GoogleDriveCache
import `in`.jphe.storyvox.source.pdf.parse.PdfChapterBuilder
import `in`.jphe.storyvox.source.pdf.parse.PdfPage
import `in`.jphe.storyvox.source.pdf.parse.PdfTextProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #1677 — turns a downloaded Drive EPUB or PDF into narratable
 * chapters. A seam (not a direct call) because both real parsers are
 * Android-bound — [EpubParser] rides `android.util.Xml`, the PDF text layer
 * rides the app-side PdfBox [PdfTextProvider] — and the source's unit and
 * contract tests are plain JVM. Tests hand [`in`.jphe.storyvox.source.googledrive.GoogleDriveSource]
 * a fake; production binds [DefaultDriveBookReader].
 */
interface DriveBookReader {
    /** Parse a whole `.epub` (zip bytes) into spine-ordered chapters. */
    suspend fun readEpub(bytes: ByteArray): DriveBook

    /** Extract a PDF's text layer (OCR fallback per the app-side provider)
     *  and group its pages into chapters. [fileId] names the on-disk cache
     *  copy the PDF provider opens (it reads by URI, not bytes). */
    suspend fun readPdf(fileId: String, bytes: ByteArray): DriveBook
}

/** A parsed binary Drive file: [author] may be blank; [chapters] in order. */
data class DriveBook(
    val author: String,
    val chapters: List<DriveChapter>,
)

/** One narratable chapter of a parsed Drive file. */
data class DriveChapter(
    val title: String,
    val plainBody: String,
)

/** Thrown when a downloaded file can't be parsed (corrupt zip, not a PDF). */
class DriveParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Production [DriveBookReader]: reuses `:source-epub`'s [EpubParser] and
 * `:source-pdf`'s [PdfTextProvider] + [PdfChapterBuilder] so Drive books read
 * exactly like locally imported ones (same chapter split, same OCR fallback
 * for scanned PDF pages, same HTML→plain normalisation).
 */
@Singleton
class DefaultDriveBookReader @Inject constructor(
    @GoogleDriveCache private val cacheDir: File,
    private val pdfText: PdfTextProvider,
) : DriveBookReader {

    override suspend fun readEpub(bytes: ByteArray): DriveBook = withContext(Dispatchers.IO) {
        val book = try {
            EpubParser.parseFromBytes(bytes)
        } catch (e: Exception) {
            throw DriveParseException("Could not read this EPUB: ${e.message}", e)
        }
        DriveBook(
            author = book.author,
            chapters = book.chapters.mapNotNull { ch ->
                val plain = (ch.plainBody ?: ch.htmlBody.htmlToPlainText()).trim()
                if (plain.isEmpty()) null else DriveChapter(title = ch.title, plainBody = plain)
            },
        )
    }

    override suspend fun readPdf(fileId: String, bytes: ByteArray): DriveBook {
        val file = withContext(Dispatchers.IO) {
            cacheDir.mkdirs()
            // fileIds are Drive's URL-safe alphabet; sanitise anyway so a
            // hostile id can never escape the cache dir.
            File(cacheDir, fileId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".pdf").apply {
                writeBytes(bytes)
            }
        }
        val uri = "file://" + file.absolutePath
        val count = pdfText.pageCount(uri)
        if (count <= 0) throw DriveParseException("Could not open this PDF")
        val pages = (0 until count).map { idx ->
            PdfPage(index = idx, text = pdfText.pageText(uri, idx).orEmpty())
        }
        return DriveBook(
            author = pdfText.author(uri),
            chapters = PdfChapterBuilder.build(pages).map {
                DriveChapter(title = it.title, plainBody = it.plainBody)
            },
        )
    }
}
