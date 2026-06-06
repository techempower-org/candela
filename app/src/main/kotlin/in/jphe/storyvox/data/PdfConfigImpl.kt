package `in`.jphe.storyvox.data

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

    override val documents: Flow<List<PdfFileEntry>> = folderUriString.map { uri ->
        if (uri == null) emptyList() else withContext(Dispatchers.IO) { enumerator.enumerate(uri) }
    }.distinctUntilChanged()

    override suspend fun documents(): List<PdfFileEntry> {
        val uri = snapshot() ?: return emptyList()
        return withContext(Dispatchers.IO) { enumerator.enumerate(uri) }
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
