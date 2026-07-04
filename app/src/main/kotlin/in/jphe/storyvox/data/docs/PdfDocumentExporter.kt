package `in`.jphe.storyvox.data.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Issue #1513 — production [DocPdfExporter] backed by Android's
 * [android.graphics.pdf.PdfDocument].
 *
 * Each captured page (a `content://`/`file://` image URI) is decoded,
 * downscaled so its long edge is at most [MAX_EDGE_PX] — the single
 * biggest lever on output size — and drawn, aspect-fit and centred, onto
 * a US-Letter PDF page (portrait or landscape to match the scan). The
 * result is written to `cacheDir/exports/`, which the app's FileProvider
 * shares via `ACTION_SEND` (authority `${packageName}.fileprovider`,
 * scoped to `exports/` in `xml/file_paths.xml`).
 *
 * Runs entirely on-device (no network); the scanned image, and the PDF
 * built from it, never leave the phone.
 */
@Singleton
class PdfDocumentExporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : DocPdfExporter {

    override suspend fun exportToPdf(request: DocPdfRequest): DocPdfResult =
        withContext(Dispatchers.IO) {
            if (request.pages.isEmpty()) {
                return@withContext DocPdfResult.Failure(
                    "Add at least one page before exporting.",
                )
            }
            val document = PdfDocument()
            try {
                var placed = 0
                request.pages.forEachIndexed { index, page ->
                    val bitmap = decodeScaled(Uri.parse(page.uri)) ?: return@forEachIndexed
                    try {
                        drawPage(document, bitmap, pageNumber = placed + 1)
                        placed++
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (placed == 0) {
                    return@withContext DocPdfResult.Failure(
                        "Couldn't read any of the captured pages. Try re-scanning.",
                    )
                }
                val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                val fileName = safeFileName(request.title)
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { document.writeTo(it) }
                DocPdfResult.Success(
                    filePath = outFile.absolutePath,
                    fileName = fileName,
                    pageCount = placed,
                    byteSize = outFile.length(),
                )
            } catch (t: Throwable) {
                DocPdfResult.Failure("Couldn't build the PDF. Please try again.", t)
            } finally {
                document.close()
            }
        }

    /** Draw [bitmap] aspect-fit + centred on a Letter page whose
     *  orientation matches the bitmap. */
    private fun drawPage(document: PdfDocument, bitmap: Bitmap, pageNumber: Int) {
        val landscape = bitmap.width > bitmap.height
        val pageW = if (landscape) LETTER_LONG_PT else LETTER_SHORT_PT
        val pageH = if (landscape) LETTER_SHORT_PT else LETTER_LONG_PT
        val info = PdfDocument.PageInfo.Builder(pageW, pageH, pageNumber).create()
        val pdfPage = document.startPage(info)
        val canvas = pdfPage.canvas
        canvas.drawColor(Color.WHITE)
        val scale = minOf(pageW.toFloat() / bitmap.width, pageH.toFloat() / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (pageW - drawW) / 2f
        val top = (pageH - drawH) / 2f
        val dst = RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(bitmap, null, dst, FILTER_PAINT)
        document.finishPage(pdfPage)
    }

    /**
     * Decode [uri] with an `inSampleSize` that keeps the long edge at or
     * below [MAX_EDGE_PX], then fine-scale if a power-of-two sample left
     * it oversized. Two-pass (bounds first) so we never allocate the
     * full-resolution bitmap for an 8-megapixel camera shot.
     */
    private fun decodeScaled(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        var sample = 1
        while (longEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val curLong = maxOf(decoded.width, decoded.height)
        if (curLong <= MAX_EDGE_PX) return decoded
        val ratio = MAX_EDGE_PX.toFloat() / curLong
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * ratio).toInt().coerceAtLeast(1),
            (decoded.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun openStream(uri: Uri) =
        runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()

    /** Derive a filesystem-safe `.pdf` name from the user's title. */
    private fun safeFileName(title: String): String {
        val base = title.trim()
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .replace(Regex("\\s+"), "-")
            .take(60)
            .trim('-')
            .ifBlank { "Scanned-document" }
        return "$base.pdf"
    }

    companion object {
        /** Longest bitmap edge, in pixels, before drawing into the PDF.
         *  ~150 DPI on a Letter page — legible for text scans and keeps a
         *  multi-page packet well under an email-friendly size. */
        @VisibleForTesting
        const val MAX_EDGE_PX = 1500

        private const val EXPORT_DIR = "exports"

        // US Letter in PostScript points (1/72"): 8.5" x 11".
        private const val LETTER_SHORT_PT = 612
        private const val LETTER_LONG_PT = 792

        private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
