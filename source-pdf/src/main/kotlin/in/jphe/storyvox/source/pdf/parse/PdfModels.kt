package `in`.jphe.storyvox.source.pdf.parse

/**
 * Issue #996 — minimal PDF model. Captures only what storyvox's
 * reader / audiobook needs: title, author, and an ordered list of
 * chapters with their plain-text bodies.
 *
 * Unlike EPUB (which carries semantic spine + per-file HTML), a PDF is
 * a flat sequence of pages. We extract plain text per page and group
 * pages into chapters (see [PdfChapterBuilder]). The pipeline
 * downstream (EngineStreamingSource → TTS) only ever consumes plain
 * text, so we never synthesize HTML — the PDF text layer is already
 * the narratable content.
 */
data class PdfDocument(
    /** /Title from the document-information dictionary, or empty when
     *  the PDF carries no title (falls back to the filename upstream). */
    val title: String,
    /** /Author from the document-information dictionary, or empty
     *  (preserves the FictionSummary contract of non-null author). */
    val author: String,
    /** Chapters in reading order. Each carries the concatenated plain
     *  text of the pages grouped into that chapter. */
    val chapters: List<PdfChapter>,
)

data class PdfChapter(
    /** Stable per-document chapter id — `page-<firstPageIndex>`. Stable
     *  across re-opens of the same PDF since page order is fixed. */
    val id: String,
    /** Display title. PDFs rarely carry per-chapter titles inline; we
     *  use the detected heading when chapter-by-heading splitting fires,
     *  otherwise "Pages N–M" / "Page N". */
    val title: String,
    /** 0-based position in the chapter list. */
    val index: Int,
    /** 0-based index of the first source page in this chapter. */
    val firstPageIndex: Int,
    /** Plain-text body of the chapter (pages joined). */
    val plainBody: String,
)

/** One extracted page: its 0-based index and the plain text pulled
 *  from the text layer (or OCR fallback). [text] is blank for a page
 *  with no extractable text that OCR also could not recover. */
data class PdfPage(
    val index: Int,
    val text: String,
)
