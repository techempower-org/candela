package `in`.jphe.storyvox.source.pdf.config

import kotlinx.coroutines.flow.Flow

/**
 * Issue #996 — abstraction over the PDF-source's persistent folder
 * selection. Direct mirror of `:source-epub`'s `EpubConfig` (#235):
 * the implementation lives in `:app` because persisting a SAF-tree URI
 * requires Android-specific permission plumbing
 * (ContentResolver.takePersistableUriPermission); this interface is
 * what the source consumes.
 *
 * One folder URI per install, by design — the typical user has a
 * single Documents / Downloads / Papers folder where their PDFs land.
 * Multi-folder support is a follow-up if anyone asks.
 *
 * The folderUriString persists as `String` rather than `android.net.Uri`
 * so this interface stays free of Android types — keeps the source
 * module testable without Robolectric.
 */
interface PdfConfig {
    /** Hot stream of the currently-configured folder URI string.
     *  Null when no folder has been picked. */
    val folderUriString: Flow<String?>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun snapshot(): String?

    /** Hot stream of indexed PDF files in the folder. Each entry's
     *  [PdfFileEntry.uriString] is what the source pulls text from
     *  via the app-side [in.jphe.storyvox.source.pdf.parse.PdfTextProvider]. */
    val documents: Flow<List<PdfFileEntry>>

    /** Synchronous snapshot of the indexed-documents list. */
    suspend fun documents(): List<PdfFileEntry>
}

/**
 * One indexed PDF file. The fictionId is a stable hash of the URI
 * string — same file at the same SAF path resolves to the same id
 * across re-launches. Display name comes from DocumentFile.getName()
 * (just the filename) until [in.jphe.storyvox.source.pdf.PdfSource.fictionDetail]
 * hydrates with the real title (where the PDF carries a /Title in its
 * document-information dictionary).
 */
data class PdfFileEntry(
    val fictionId: String,
    val uriString: String,
    val displayName: String,
)

/** Issue #996 — derive the persistent fictionId from a SAF URI.
 *  Stable across re-launches; collision-resistant for the small N
 *  (a few hundred PDF files at most per user) storyvox cares about.
 *  Same shape as [in.jphe.storyvox.source.epub.config] fictionId. */
fun fictionIdForPdfUri(uriString: String): String {
    val canonical = uriString.trim()
    val hash = canonical.hashCode().toUInt().toString(16).padStart(8, '0')
    return "pdf:$hash"
}
