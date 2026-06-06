package `in`.jphe.storyvox.data

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.pdf.parse.PdfOcrTextProvider
import `in`.jphe.storyvox.source.pdf.parse.PdfTextProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Issue #996 — production [PdfTextProvider] backed by PdfBox-Android.
 * Lives in `:app` (not `:source-pdf`) because PdfBox-Android needs an
 * Android [Context] for its font/resource loader, mirroring how
 * [EpubConfigImpl] owns the SAF plumbing for `:source-epub`.
 *
 * Extraction runs on [Dispatchers.IO] — the codebase flags main-thread
 * file reads as bugs (#864).
 *
 * Per-page strategy:
 *  1. Pull the page's text via [PDFTextStripper] (born-digital text
 *     layer).
 *  2. When that yields blank text — a scanned / image-only page — fall
 *     back to the injected [PdfOcrTextProvider] (#995). Phase 1 binds
 *     [in.jphe.storyvox.source.pdf.parse.NoOpPdfOcrTextProvider], so
 *     scanned pages return null and are skipped; #995 swaps in a real
 *     ML Kit provider with no change here.
 *
 * Each call re-opens the document. PdfBox's [PDDocument] is not safe to
 * cache across coroutines, and the source layer already re-extracts per
 * fictionDetail / chapter; if profiling shows this is hot, a short-TTL
 * per-URI byte cache is the cheap win.
 */
@Singleton
class PdfBoxTextProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocr: PdfOcrTextProvider,
) : PdfTextProvider {

    /** PdfBox-Android requires PDFBoxResourceLoader.init(context) once
     *  before any document is opened. We do it lazily (first PDF use)
     *  rather than in Application.onCreate so a session that never
     *  touches a PDF pays nothing. */
    private fun ensureInit() {
        if (initialized.compareAndSet(false, true)) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    override suspend fun pageCount(uriString: String): Int = withContext(Dispatchers.IO) {
        ensureInit()
        runCatching {
            openDocument(uriString)?.use { it.numberOfPages }
        }.getOrNull() ?: 0
    }

    override suspend fun pageText(uriString: String, pageIndex: Int): String? =
        withContext(Dispatchers.IO) {
            ensureInit()
            val layerText = runCatching {
                openDocument(uriString)?.use { doc ->
                    if (pageIndex !in 0 until doc.numberOfPages) return@use null
                    val stripper = PDFTextStripper().apply {
                        // PDFTextStripper page numbers are 1-based.
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }
                    stripper.getText(doc)
                }
            }.getOrNull()?.trim()

            if (!layerText.isNullOrBlank()) {
                layerText
            } else {
                // No text layer on this page (scanned image) — route the
                // rendered page through the OCR seam (#995). Phase 1
                // default returns null and the page is skipped upstream.
                ocr.ocrPage(uriString, pageIndex)?.trim()?.takeIf { it.isNotBlank() }
            }
        }

    override suspend fun title(uriString: String): String = withContext(Dispatchers.IO) {
        ensureInit()
        runCatching {
            openDocument(uriString)?.use { it.documentInformation?.title.orEmpty().trim() }
        }.getOrNull().orEmpty()
    }

    override suspend fun author(uriString: String): String = withContext(Dispatchers.IO) {
        ensureInit()
        runCatching {
            openDocument(uriString)?.use { it.documentInformation?.author.orEmpty().trim() }
        }.getOrNull().orEmpty()
    }

    /** Open the PDF behind a SAF `content://` URI as a PdfBox
     *  [PDDocument]. Returns null when the file can't be opened. */
    private fun openDocument(uriString: String): PDDocument? {
        val stream = context.contentResolver.openInputStream(Uri.parse(uriString)) ?: return null
        return stream.use { PDDocument.load(it) }
    }

    private companion object {
        val initialized = AtomicBoolean(false)
    }
}
