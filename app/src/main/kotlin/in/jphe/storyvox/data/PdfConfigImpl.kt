package `in`.jphe.storyvox.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.pdf.config.PdfConfig
import `in`.jphe.storyvox.source.pdf.config.PdfFileEntry
import `in`.jphe.storyvox.source.pdf.config.fictionIdForPdfUri
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.pdfDataStore: DataStore<Preferences> by preferencesDataStore(name = "storyvox_pdf")

private object PdfKeys {
    /** Persisted SAF tree URI (the user-picked folder). The
     *  ContentResolver.takePersistableUriPermission grant survives
     *  reboots — we don't re-prompt. Empty / missing = no folder
     *  configured (Browse → Local PDFs shows empty state). */
    val FOLDER_URI = stringPreferencesKey("pref_pdf_folder_uri")

    /** Issue #1228 — single PDFs imported via the in-app "Import a file…"
     *  picker (vs the folder picker). Each member is one encoded record
     *  (`displayName|uriString` — see [encodeImportedPdf] /
     *  [decodeImportedPdf]). A `Set<String>` dedups by exact record; the
     *  indexed list also keys on fictionId so re-importing the same
     *  document doesn't create a duplicate fiction. Mirrors the
     *  [EpubConfigImpl] #1000 import store; PDFs carry no entry-kind so
     *  the record is one field shorter. */
    val IMPORTED_FILES = stringSetPreferencesKey("pref_pdf_imported_files")
}

/** Issue #1228 — encoded `Set<String>` record for one imported PDF.
 *  Pipe-delimited; the uriString is last so it can legally contain the
 *  delimiter (we `substringAfter` the first pipe). */
private fun encodeImportedPdf(entry: PdfFileEntry): String =
    "${entry.displayName.replace('|', '_')}|${entry.uriString}"

private fun decodeImportedPdf(record: String): PdfFileEntry? {
    val pipe = record.indexOf('|')
    if (pipe < 0) return null
    val displayName = record.substring(0, pipe)
    val uriString = record.substring(pipe + 1)
    if (uriString.isBlank()) return null
    return PdfFileEntry(
        fictionId = fictionIdForPdfUri(uriString),
        uriString = uriString,
        displayName = displayName,
    )
}

/** Decode the persisted import-record set into entries, sorted by
 *  display name. Malformed records are dropped silently (forward-
 *  compat / corruption resilience). Mirrors [EpubConfigImpl]'s decoder. */
private fun decodeImportedPdfSet(records: Set<String>?): List<PdfFileEntry> =
    records.orEmpty()
        .mapNotNull { decodeImportedPdf(it) }
        .sortedBy { it.displayName.lowercase() }

/** Issue #1228 — merge folder-enumerated PDFs with single-file imports.
 *  Folder entries win on fictionId collision (the folder is the canonical
 *  surface); imports for files not in the folder are appended. Stable
 *  order: folder documents first, then imports. Mirrors [mergeBooks]. */
private fun mergeDocuments(
    folder: List<PdfFileEntry>,
    imported: List<PdfFileEntry>,
): List<PdfFileEntry> {
    if (imported.isEmpty()) return folder
    val folderIds = folder.mapTo(HashSet()) { it.fictionId }
    return folder + imported.filter { it.fictionId !in folderIds }
}

/**
 * Issue #996 — abstraction over SAF folder enumeration for the PDF
 * backend. Direct mirror of [EpubConfigImpl] (#235): production impl
 * lives here in `:app`; test fakes pass a no-op enumerator and avoid
 * pulling Robolectric into the settings-test classpath.
 */
internal interface PdfFileEnumerator {
    fun enumerate(treeUriString: String): List<PdfFileEntry>
}

private class SafPdfFileEnumerator(
    private val context: Context,
) : PdfFileEnumerator {

    override fun enumerate(treeUriString: String): List<PdfFileEntry> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString)) ?: return emptyList()
        if (!tree.isDirectory) return emptyList()
        return tree.listFiles()
            .asSequence()
            .filter { it.isFile && (it.name?.endsWith(".pdf", ignoreCase = true) ?: false) }
            .mapNotNull { f ->
                val name = f.name ?: return@mapNotNull null
                val uriStr = f.uri.toString()
                PdfFileEntry(
                    fictionId = fictionIdForPdfUri(uriStr),
                    uriString = uriStr,
                    displayName = name,
                )
            }
            .sortedBy { it.displayName.lowercase() }
            .toList()
    }
}

/**
 * Production [PdfConfig] (#996) backed by a tiny dedicated DataStore +
 * Android's Storage Access Framework. Same pattern as [EpubConfigImpl]
 * — the source module stays Android-types-free; this impl owns the SAF
 * plumbing.
 */
@Singleton
class PdfConfigImpl internal constructor(
    private val store: DataStore<Preferences>,
    private val enumerator: PdfFileEnumerator,
) : PdfConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(
        store = context.pdfDataStore,
        enumerator = SafPdfFileEnumerator(context),
    )

    override val folderUriString: Flow<String?> = store.data
        .map { prefs -> prefs[PdfKeys.FOLDER_URI]?.takeIf { it.isNotBlank() } }
        .distinctUntilChanged()

    override suspend fun snapshot(): String? =
        store.data.first()[PdfKeys.FOLDER_URI]?.takeIf { it.isNotBlank() }

    /** Issue #1228 — single PDFs imported via the in-app picker, decoded
     *  from the [PdfKeys.IMPORTED_FILES] preference set. Independent of the
     *  folder picker; merged into [documents] below. */
    private val importedFiles: Flow<List<PdfFileEntry>> = store.data
        .map { prefs -> decodeImportedPdfSet(prefs[PdfKeys.IMPORTED_FILES]) }
        .distinctUntilChanged()

    override val documents: Flow<List<PdfFileEntry>> =
        combine(folderUriString, importedFiles) { uri, imported ->
            val folder = if (uri == null) emptyList()
            else withContext(Dispatchers.IO) { enumerator.enumerate(uri) }
            mergeDocuments(folder, imported)
        }.distinctUntilChanged()

    override suspend fun documents(): List<PdfFileEntry> {
        val folder = snapshot()?.let { withContext(Dispatchers.IO) { enumerator.enumerate(it) } }
            ?: emptyList()
        val imported = decodeImportedPdfSet(store.data.first()[PdfKeys.IMPORTED_FILES])
        return mergeDocuments(folder, imported)
    }

    /**
     * Mutator hooks for Settings UI. Kept on the impl rather than the
     * [PdfConfig] interface — same reasoning as [EpubConfigImpl]. The
     * caller takes persistable URI permission via
     * [android.content.ContentResolver.takePersistableUriPermission]
     * before calling [setFolder]; otherwise the grant evaporates on
     * next launch.
     */
    suspend fun setFolder(uriString: String) {
        store.edit { prefs ->
            if (uriString.isBlank()) prefs.remove(PdfKeys.FOLDER_URI)
            else prefs[PdfKeys.FOLDER_URI] = uriString.trim()
        }
    }

    suspend fun clearFolder() {
        store.edit { prefs -> prefs.remove(PdfKeys.FOLDER_URI) }
    }

    /**
     * Issue #1228 — register a single PDF picked via the in-app "Import a
     * file…" flow as a standalone fiction, independent of the folder
     * picker. The caller (the shared document-import path) is expected to
     * have already taken persistable read permission on the Uri via
     * [android.content.ContentResolver.takePersistableUriPermission] so the
     * grant survives the next launch (re-opens from Library work). Returns
     * the stable fictionId the caller navigates to.
     *
     * Idempotent on the Uri: the persisted record set is keyed by the
     * exact encoded record, and the indexed list dedups by fictionId
     * ([fictionIdForPdfUri] is a stable hash of the Uri), so importing the
     * same file twice doesn't create a second fiction. Direct mirror of
     * [EpubConfigImpl.importFile].
     */
    suspend fun importFile(
        uriString: String,
        displayName: String,
    ): String {
        // #1265 — normalize the Uri ONCE and hash the normalized form, so the
        // stored `uriString` and the `fictionId` derived from it agree. Hashing
        // the raw string while storing the trimmed one meant a whitespace-padded
        // Uri yielded an id that later (trimmed) lookups couldn't match.
        val normalizedUri = uriString.trim()
        val entry = PdfFileEntry(
            fictionId = fictionIdForPdfUri(normalizedUri),
            uriString = normalizedUri,
            displayName = displayName.ifBlank { normalizedUri.substringAfterLast('/') },
        )
        store.edit { prefs ->
            val existing = prefs[PdfKeys.IMPORTED_FILES].orEmpty()
            // Drop any prior record for the same fictionId (e.g. the
            // display name changed) before adding the fresh one.
            val pruned = existing.filterTo(mutableSetOf()) { rec ->
                decodeImportedPdf(rec)?.fictionId != entry.fictionId
            }
            pruned.add(encodeImportedPdf(entry))
            prefs[PdfKeys.IMPORTED_FILES] = pruned
        }
        return entry.fictionId
    }

    companion object {
        /** Test factory — no-op enumerator for unit tests that just
         *  need the dependency to satisfy the constructor. */
        internal fun forTesting(store: DataStore<Preferences>): PdfConfigImpl =
            PdfConfigImpl(
                store = store,
                enumerator = object : PdfFileEnumerator {
                    override fun enumerate(treeUriString: String): List<PdfFileEntry> = emptyList()
                },
            )
    }
}
