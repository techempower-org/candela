package `in`.jphe.storyvox.source.pdf.parse

/**
 * Issue #996 — the seam between the PDF source (this module) and the
 * Android-bound text extraction (app-side, backed by PdfBox-Android).
 *
 * Lives here, in the source module, so [PdfSource] and the unit tests
 * can depend on it without pulling in `android.*` / PdfBox. The
 * production implementation lives in `:app` (it needs an Android
 * `Context` for PdfBox's font loading and `PdfRenderer`), mirroring how
 * `EpubConfigImpl` owns the SAF plumbing for `:source-epub`.
 *
 * Extraction is expected to run off the main thread (the codebase flags
 * main-thread file reads as bugs — see #864); the impl wraps PdfBox in
 * `withContext(Dispatchers.IO)`.
 */
interface PdfTextProvider {

    /** Total page count of the PDF at [uriString], or 0 when the file
     *  cannot be opened (moved, permission revoked, corrupt). */
    suspend fun pageCount(uriString: String): Int

    /**
     * Plain text for page [pageIndex] (0-based) of the PDF at
     * [uriString]. Returns the text-layer content when the page is
     * born-digital. Returns null / blank for a scanned-image page that
     * carries no text layer — the impl is responsible for invoking the
     * [PdfOcrTextProvider] fallback (#995) before giving up, so callers
     * of this interface never have to know whether the text came from
     * the text layer or OCR.
     */
    suspend fun pageText(uriString: String, pageIndex: Int): String?

    /** Document-information dictionary title (/Title), or empty. */
    suspend fun title(uriString: String): String

    /** Document-information dictionary author (/Author), or empty. */
    suspend fun author(uriString: String): String
}

/**
 * Issue #995 (OCR) integration seam — owned by Morpheus. A scanned /
 * image-only PDF page yields no text from the PDF text layer; the
 * app-side [PdfTextProvider] renders that page to a bitmap (via
 * Android's `PdfRenderer`) and routes it here for optical character
 * recognition.
 *
 * Phase 1 (#996) ships [NoOpPdfOcrTextProvider] as the default Hilt
 * binding: it returns null, so scanned PDFs surface their born-digital
 * pages and skip the image-only ones. When #995 lands, Morpheus's ML
 * Kit-backed implementation replaces the binding and scanned PDFs
 * become fully narratable — no change required in [PdfSource] or the
 * [PdfTextProvider] contract.
 *
 * Contract note (coordinated via Sandman): the OCR provider receives
 * the source [uriString] + [pageIndex] so it can decide how to obtain
 * the bitmap (the app-side text provider can also hand it a pre-rendered
 * bitmap through an app-internal overload; the cross-module seam stays
 * Android-types-free here).
 */
interface PdfOcrTextProvider {
    /**
     * OCR the rendered image of page [pageIndex] (0-based) of the PDF
     * at [uriString]. Returns the recognized text, or null when OCR is
     * unavailable (Phase 1 default) or recognition failed / found
     * nothing.
     */
    suspend fun ocrPage(uriString: String, pageIndex: Int): String?
}

/**
 * Default OCR seam (#996 Phase 1) — no OCR. Returns null for every
 * page so the PDF source degrades gracefully on scanned documents
 * (born-digital pages still narrate) until #995 swaps in a real
 * ML Kit-backed [PdfOcrTextProvider].
 */
class NoOpPdfOcrTextProvider : PdfOcrTextProvider {
    override suspend fun ocrPage(uriString: String, pageIndex: Int): String? = null
}
