package `in`.jphe.storyvox.data.docs

/**
 * Issue #1512 — on-device seam that fills a photographed/scanned paper
 * form and exports a **flattened** PDF that looks like the completed
 * paper form.
 *
 * Per the #1512 research verdict, v1 does NOT use PDF AcroForm fields:
 * the input is a *raster* image of a paper form (there are no digital
 * form-field widgets to fill), and a flattened export renders
 * identically in Google Drive / Files / print. The production impl
 * (`PdfDocumentExporter` in `:app`, which also implements
 * [DocPdfExporter]) draws the page bitmap then composites the user's
 * text / checkmarks / drawn signature onto the PDF canvas via
 * `android.graphics.pdf.PdfDocument`. No AcroForm dependency, no network.
 *
 * ## Coordinates are normalized
 *
 * Every overlay position is expressed as a fraction (0..1) of the page
 * image's width/height, so placement is resolution-independent: the UI
 * captures a tap relative to however large the page is drawn on screen,
 * and the exporter maps the same fraction onto the PDF page. This keeps
 * the seam free of any pixel/`android.graphics` type — the ViewModel
 * stays plain JVM and unit-testable with a fake.
 *
 * Reuses [DocPdfResult] (the #1513 export result) so callers share one
 * success/failure shape.
 */
interface FormPdfExporter {

    /**
     * Draw [FormPdfRequest.pageImageUri] and composite its [overlays]
     * into a single flattened PDF written to the app export cache. Runs
     * on a background dispatcher; never throws. An undecodable page image
     * returns [DocPdfResult.Failure].
     */
    suspend fun exportFilledForm(request: FormPdfRequest): DocPdfResult
}

/** One fill-and-export job. */
data class FormPdfRequest(
    val title: String,
    /** `content://`/`file://` URI (as a String) of the form page image. */
    val pageImageUri: String,
    val overlays: List<FormOverlay>,
)

/** A normalized point (each coordinate a fraction 0..1). */
data class NormPoint(val x: Float, val y: Float)

/**
 * One thing composited onto the form. All positions/sizes are fractions
 * (0..1) of the page image.
 */
sealed interface FormOverlay {

    /**
     * User-typed text placed with its baseline-ish top-left at
     * ([x], [y]). [heightFraction] sets the text size as a fraction of
     * the page height so it scales with the form.
     */
    data class TextBox(
        val x: Float,
        val y: Float,
        val text: String,
        val heightFraction: Float = 0.02f,
    ) : FormOverlay

    /** A checkmark centred at ([x], [y]); [sizeFraction] of page height. */
    data class Checkmark(
        val x: Float,
        val y: Float,
        val sizeFraction: Float = 0.025f,
    ) : FormOverlay

    /**
     * A drawn signature bounded by the box ([x], [y], [widthFraction],
     * [heightFraction]) on the page. [strokes] is a list of polylines;
     * each point is normalized **within the signature box** (0..1).
     */
    data class Signature(
        val x: Float,
        val y: Float,
        val widthFraction: Float,
        val heightFraction: Float,
        val strokes: List<List<NormPoint>>,
    ) : FormOverlay
}
