package `in`.jphe.storyvox.data.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
) : DocPdfExporter, FormPdfExporter {

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
        val dst = fitRect(bitmap, pageW, pageH)
        canvas.drawBitmap(bitmap, null, dst, FILTER_PAINT)
        document.finishPage(pdfPage)
    }

    // ── #1512: fill a photographed form → flattened PDF ────────────────

    override suspend fun exportFilledForm(request: FormPdfRequest): DocPdfResult =
        withContext(Dispatchers.IO) {
            val bitmap = decodeScaled(Uri.parse(request.pageImageUri), FORM_MAX_EDGE_PX)
                ?: return@withContext DocPdfResult.Failure(
                    "Couldn't read the form image. Try re-scanning.",
                )
            val document = PdfDocument()
            try {
                val landscape = bitmap.width > bitmap.height
                val pageW = if (landscape) LETTER_LONG_PT else LETTER_SHORT_PT
                val pageH = if (landscape) LETTER_SHORT_PT else LETTER_LONG_PT
                val info = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                val pdfPage = document.startPage(info)
                val canvas = pdfPage.canvas
                canvas.drawColor(Color.WHITE)
                // The image rect the overlays are positioned relative to.
                val dst = fitRect(bitmap, pageW, pageH)
                canvas.drawBitmap(bitmap, null, dst, FILTER_PAINT)
                request.overlays.forEach { drawOverlay(canvas, dst, pageH, it) }
                document.finishPage(pdfPage)

                val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
                val fileName = safeFileName(request.title)
                val outFile = File(dir, fileName)
                FileOutputStream(outFile).use { document.writeTo(it) }
                DocPdfResult.Success(
                    filePath = outFile.absolutePath,
                    fileName = fileName,
                    pageCount = 1,
                    byteSize = outFile.length(),
                )
            } catch (t: Throwable) {
                DocPdfResult.Failure("Couldn't build the filled PDF. Please try again.", t)
            } finally {
                bitmap.recycle()
                document.close()
            }
        }

    /** Composite one overlay onto the page canvas. [dst] is the drawn
     *  image rect; overlay coords are fractions of it. [pageHeightPt]
     *  scales text/marks so they read consistently across page sizes. */
    private fun drawOverlay(canvas: Canvas, dst: RectF, pageHeightPt: Int, overlay: FormOverlay) {
        when (overlay) {
            is FormOverlay.TextBox -> {
                if (overlay.text.isBlank()) return
                val px = dst.left + overlay.x * dst.width()
                val py = dst.top + overlay.y * dst.height()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = overlay.heightFraction * pageHeightPt
                }
                // y is the field's top; drawText draws from the baseline, so
                // offset down by the font ascent to sit inside the blank.
                val baseline = py - paint.fontMetrics.ascent
                canvas.drawText(overlay.text, px, baseline, paint)
            }

            is FormOverlay.Checkmark -> {
                val cx = dst.left + overlay.x * dst.width()
                val cy = dst.top + overlay.y * dst.height()
                val s = overlay.sizeFraction * pageHeightPt
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = (s * 0.14f).coerceAtLeast(1.2f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                val path = Path().apply {
                    moveTo(cx - s * 0.45f, cy)
                    lineTo(cx - s * 0.1f, cy + s * 0.4f)
                    lineTo(cx + s * 0.5f, cy - s * 0.45f)
                }
                canvas.drawPath(path, paint)
            }

            is FormOverlay.Signature -> {
                val boxLeft = dst.left + overlay.x * dst.width()
                val boxTop = dst.top + overlay.y * dst.height()
                val boxW = overlay.widthFraction * dst.width()
                val boxH = overlay.heightFraction * dst.height()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.STROKE
                    strokeWidth = (boxH * 0.03f).coerceAtLeast(1.2f)
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
                overlay.strokes.forEach { stroke ->
                    if (stroke.size < 2) return@forEach
                    val path = Path()
                    stroke.forEachIndexed { i, p ->
                        val x = boxLeft + p.x * boxW
                        val y = boxTop + p.y * boxH
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
    }

    /** The rect a bitmap occupies when aspect-fit + centred on a
     *  [pageW]×[pageH] page. */
    private fun fitRect(bitmap: Bitmap, pageW: Int, pageH: Int): RectF {
        val scale = minOf(pageW.toFloat() / bitmap.width, pageH.toFloat() / bitmap.height)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (pageW - drawW) / 2f
        val top = (pageH - drawH) / 2f
        return RectF(left, top, left + drawW, top + drawH)
    }

    /**
     * Decode [uri] with an `inSampleSize` that keeps the long edge at or
     * below [MAX_EDGE_PX], then fine-scale if a power-of-two sample left
     * it oversized. Two-pass (bounds first) so we never allocate the
     * full-resolution bitmap for an 8-megapixel camera shot.
     */
    private fun decodeScaled(uri: Uri, maxEdge: Int = MAX_EDGE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        if (longEdge <= 0) return null

        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = openStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null

        val curLong = maxOf(decoded.width, decoded.height)
        if (curLong <= maxEdge) return decoded
        val ratio = maxEdge.toFloat() / curLong
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

        /** #1512 — forms carry denser text than a plain scan; keep the
         *  page image sharper (the overlays we draw are crisp vectors, but
         *  the underlying form must stay legible). */
        @VisibleForTesting
        const val FORM_MAX_EDGE_PX = 2200

        private const val EXPORT_DIR = "exports"

        // US Letter in PostScript points (1/72"): 8.5" x 11".
        private const val LETTER_SHORT_PT = 612
        private const val LETTER_LONG_PT = 792

        private val FILTER_PAINT = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    }
}
