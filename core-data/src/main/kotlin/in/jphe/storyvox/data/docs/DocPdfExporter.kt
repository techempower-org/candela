package `in`.jphe.storyvox.data.docs

/**
 * Issue #1513 — on-device seam that composes captured page images into
 * one compact, shareable PDF ("no scanner at home").
 *
 * The production implementation (`PdfDocumentExporter` in `:app`) draws
 * each page bitmap onto an `android.graphics.pdf.PdfDocument`,
 * downscaling so the result stays email-sized (~100-300 KB/page). It
 * runs entirely on-device: nothing about the user's scanned ID / pay
 * stub / award letter leaves the phone — the same promise as
 * techempower.org/qualify.
 *
 * ## Why this lives in :core-data
 *
 * The document scanner (#1513) and the photo→fillable-PDF flow (#1512)
 * both need PDF composition, and the encrypted wallet (#1514) re-exports
 * stored proofs the same way. Putting the seam here — alongside
 * [`in`.jphe.storyvox.data.ocr.OcrTextRecognizer] — lets the
 * `:feature` UI depend on the contract without pulling any
 * `android.graphics.*` type into the ViewModel, so the VM stays plain
 * JVM and unit-testable with a fake exporter (no Robolectric).
 *
 * ## Android-types-free by design
 *
 * Pages are addressed by their `content://` / `file://` URI **as a
 * String** ([DocPageRef.uri]), never as an `android.net.Uri`. The
 * Android impl parses the string and resolves the bytes through the
 * `ContentResolver` on its side.
 */
interface DocPdfExporter {

    /**
     * Compose [request]'s pages, in order, into a single PDF written to
     * the app's private export cache. Runs on a background dispatcher.
     * Never throws — undecodable pages are skipped, and a request that
     * yields zero usable pages returns [DocPdfResult.Failure].
     */
    suspend fun exportToPdf(request: DocPdfRequest): DocPdfResult
}

/**
 * One export job: a human [title] (used to name the output file) and the
 * ordered [pages] to place, one per PDF page.
 */
data class DocPdfRequest(
    val title: String,
    val pages: List<DocPageRef>,
)

/**
 * A single page image to place in the PDF, addressed by its
 * `content://` or `file://` URI string. Kept a String (not
 * `android.net.Uri`) so the seam carries no Android type — see the
 * class KDoc on [DocPdfExporter].
 */
data class DocPageRef(val uri: String)

/** Result of one [DocPdfExporter.exportToPdf] call. */
sealed interface DocPdfResult {

    /**
     * PDF written successfully. [filePath] is the absolute path under
     * the app's export cache (`cacheDir/exports/`), which the
     * FileProvider shares via `ACTION_SEND`. [byteSize] is the finished
     * file size so the UI can reassure the user it is email-sized.
     */
    data class Success(
        val filePath: String,
        val fileName: String,
        val pageCount: Int,
        val byteSize: Long,
    ) : DocPdfResult

    /**
     * Export could not produce a usable PDF (no pages, all pages
     * undecodable, or an I/O failure). [message] is user-facing;
     * [cause] carries the original throwable for logging.
     */
    data class Failure(val message: String, val cause: Throwable? = null) : DocPdfResult
}
