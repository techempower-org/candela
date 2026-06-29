package `in`.jphe.storyvox.feature.api

/**
 * Issue #1228 — UI-facing seam for importing a single local file
 * (EPUB / PDF / TXT) picked via Android SAF into the library as a
 * standalone fiction.
 *
 * Declared here in `:feature/api` (mirrors [FictionRepositoryUi]) so the
 * Library ViewModel can drive an import without `:feature` taking a
 * dependency on `:app`, where the `ContentResolver` + SAF plumbing and the
 * per-format import stores (`EpubConfig` / `PdfConfig`) live. The
 * implementation is bound in `AppBindings`.
 */
interface DocumentImporterUi {
    /**
     * Persist the document at [uriString] as a fiction and return the
     * outcome. The implementation resolves the document's mime type and
     * display name, classifies it, takes a persistable read grant on the
     * Uri, and registers it with the matching per-format store
     * (EPUB / TXT → EpubConfig, PDF → PdfConfig).
     *
     * [mimeHint] is a mime the caller already knows (e.g. an inbound
     * intent's `type`); pass null to let the implementation resolve it
     * from the content Uri.
     */
    suspend fun importLocalFile(uriString: String, mimeHint: String? = null): ImportFileResult
}

/** Outcome of [DocumentImporterUi.importLocalFile]. */
sealed interface ImportFileResult {
    /** Imported (or already present); [fictionId] is the stable id to
     *  navigate to. */
    data class Success(val fictionId: String) : ImportFileResult

    /** The file's type isn't one Candela can import (not EPUB / PDF / TXT). */
    data object Unsupported : ImportFileResult

    /** Import failed (unreadable Uri, permission, or store write error);
     *  [message] is a short user-facing explanation. */
    data class Error(val message: String) : ImportFileResult
}
