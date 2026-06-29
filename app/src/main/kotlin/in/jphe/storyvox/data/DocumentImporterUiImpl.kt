package `in`.jphe.storyvox.data

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.feature.api.DocumentImporterUi
import `in`.jphe.storyvox.feature.api.ImportFileResult
import `in`.jphe.storyvox.navigation.DocumentImportClassifier
import `in`.jphe.storyvox.navigation.ImportKind
import `in`.jphe.storyvox.source.epub.config.EpubEntryKind
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Issue #1228 — the shared single-file import path. Both the in-app
 * "Import a file…" picker (via [DocumentImporterUi] injected into the
 * Library ViewModel) and MainActivity's "Open With" / share ingest (#1000)
 * route through here, so the four-step sequence —
 *
 *   1. resolve the (mime, filename) pair,
 *   2. classify it via [DocumentImportClassifier],
 *   3. take a persistable read grant on the Uri,
 *   4. register it with the matching per-format store,
 *
 * — lives in exactly one place rather than being copy-pasted across the
 * two entry points.
 *
 * EPUB and plaintext register with [EpubConfigImpl] (the EPUB source
 * synthesises a one-chapter book from a `.txt` body, #1000); PDF registers
 * with [PdfConfigImpl] (#996 text-layer extraction + scanned-page OCR).
 * [DocumentImportClassifier] is the single source of truth for which
 * bucket a (mime, filename) pair falls into; the actual chapter bodies are
 * read lazily by the respective `FictionSource` when a chapter is opened.
 */
@Singleton
class DocumentImporterUiImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val epubConfig: EpubConfigImpl,
    private val pdfConfig: PdfConfigImpl,
) : DocumentImporterUi {

    override suspend fun importLocalFile(uriString: String, mimeHint: String?): ImportFileResult =
        // #1265 — the whole sequence touches blocking I/O off the bat
        // (`contentResolver.getType`, the SAF display-name query, and the
        // per-format `importFile` DataStore writes). Both entry points call
        // this from a main-ish dispatcher (viewModelScope / LaunchedEffect),
        // so offload the lot to IO rather than relying on each callee to
        // re-dispatch.
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
                ?: return@withContext ImportFileResult.Error("That file couldn't be read.")
            // intent-supplied mime (when present) wins; otherwise ask the
            // resolver. File managers are inconsistent, so the classifier also
            // falls back to the filename extension.
            val mime = mimeHint ?: runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()

            when (DocumentImportClassifier.classify(mime, displayName)) {
                ImportKind.Epub -> registerEpub(uri, uriString, displayName, EpubEntryKind.Epub)
                ImportKind.Text -> registerEpub(uri, uriString, displayName, EpubEntryKind.Text)
                ImportKind.Pdf -> registerPdf(uri, uriString, displayName)
                ImportKind.Odt -> registerOdt(uri, uriString, displayName)
                ImportKind.Unsupported -> ImportFileResult.Unsupported
            }
        }

    private suspend fun registerEpub(
        uri: Uri,
        uriString: String,
        displayName: String,
        kind: EpubEntryKind,
    ): ImportFileResult {
        persistReadGrant(uri)
        return runCatching {
            ImportFileResult.Success(epubConfig.importFile(uriString, displayName, kind))
        }.getOrElse { failure(displayName, it) }
    }

    private suspend fun registerPdf(
        uri: Uri,
        uriString: String,
        displayName: String,
    ): ImportFileResult {
        persistReadGrant(uri)
        return runCatching {
            ImportFileResult.Success(pdfConfig.importFile(uriString, displayName))
        }.getOrElse { failure(displayName, it) }
    }

    /**
     * Issue #1310 — OpenDocument Text. Unlike EPUB/PDF (which the source
     * reads lazily from the original Uri), an `.odt` body must be unzipped +
     * ODF-stripped up front, so we extract the plain text once, stage it as a
     * UTF-8 file under `filesDir`, and import THAT through the same plaintext
     * path as a `.txt`. Staging under `filesDir` (not `cacheDir`) keeps the
     * imported book readable after a cache clear; we read the source bytes
     * eagerly here, so no persistable Uri grant is needed.
     */
    private suspend fun registerOdt(
        uri: Uri,
        uriString: String,
        displayName: String,
    ): ImportFileResult {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return failure(displayName, IOException("Couldn't read the .odt file"))
        val text = OdtTextExtractor.extract(bytes)
        if (text.isBlank()) {
            return ImportFileResult.Error(
                "Couldn't read any text from ${displayName.ifBlank { "that file" }}.",
            )
        }
        return runCatching {
            val stagedUri = stageOdtText(uriString, text)
            ImportFileResult.Success(epubConfig.importFile(stagedUri, displayName, EpubEntryKind.Text))
        }.getOrElse { failure(displayName, it) }
    }

    /** Stage [text] as a UTF-8 file under `filesDir` and return its `file://`
     *  Uri string. The filename is a hash of [sourceUriString] so the same
     *  `.odt` always maps to the same staged file (and thus the same
     *  fictionId — re-importing dedupes rather than duplicating). */
    private fun stageOdtText(sourceUriString: String, text: String): String {
        val dir = File(context.filesDir, "odt-import").apply { mkdirs() }
        val staged = File(dir, Integer.toHexString(sourceUriString.hashCode()) + ".txt")
        staged.writeText(text, Charsets.UTF_8)
        return Uri.fromFile(staged).toString()
    }

    private fun failure(displayName: String, cause: Throwable): ImportFileResult {
        // #1265 — don't log the document name (private content) at warn
        // level; the cause/stack is enough to diagnose. The name still goes
        // in the user-facing error string, which isn't persisted to logcat.
        Log.w(TAG, "Import failed", cause)
        return ImportFileResult.Error("Couldn't import ${displayName.ifBlank { "that file" }}.")
    }

    /**
     * Convert the one-shot SAF read grant into a durable one so Library
     * re-opens survive a relaunch. Best-effort: some providers grant only
     * a one-shot read (no persistable flag), and `file://` Uris (the debug
     * sample seed) have nothing to persist — the import still works for
     * this session, and a failed persist must not abort the open.
     */
    private fun persistReadGrant(uri: Uri) {
        // #1265 — only content:// Uris can carry a persistable grant. A
        // file:// Uri (the debug sample seed) would just throw a caught
        // SecurityException and emit a noisy warn, so skip it outright.
        if (uri.scheme != "content") return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            // #1265 — don't log the Uri (can encode a private document path).
        }.onFailure { Log.w(TAG, "Could not persist URI permission", it) }
    }

    /** Query the SAF [OpenableColumns.DISPLAY_NAME] for a content Uri.
     *  Returns null for non-content Uris or when the provider doesn't
     *  expose the column (the caller falls back to the last path segment).
     *  Off the main thread — ContentResolver.query touches a binder.
     *  Lifted from MainActivity's #1000 ingest so both entry points resolve
     *  the display name identically. */
    private suspend fun queryDisplayName(uri: Uri): String? =
        withContext(Dispatchers.IO) {
            if (uri.scheme != "content") return@withContext null
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )?.use { cursor: Cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
        }

    private companion object {
        const val TAG = "DocumentImporter"
    }
}
