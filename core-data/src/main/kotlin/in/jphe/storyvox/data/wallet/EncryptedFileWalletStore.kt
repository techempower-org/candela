package `in`.jphe.storyvox.data.wallet

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Issue #1514 — production [WalletStore] backed by Jetpack Security
 * `EncryptedFile`.
 *
 * Everything — the scan images AND the JSON metadata manifest — is
 * AES-256-GCM encrypted at rest under an Android-Keystore-held
 * [MasterKey], in an app-private `files/wallet/` dir that is excluded
 * from cloud backup + device transfer (see `backup_rules.xml` /
 * `data_extraction_rules.xml`) and never enters `core-sync`. Nothing
 * about the household's documents leaves the phone or is stored in the
 * clear.
 *
 * ## Defense in depth (mirrors #951)
 *
 * `EncryptedFile` MACs each blob with the current install's master key.
 * A blob that survived onto a fresh install (it shouldn't — the dir is
 * backup-excluded) could not be verified and would throw. Reads catch
 * and treat the store as empty rather than crash on cold start.
 */
@Singleton
class EncryptedFileWalletStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : WalletStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val walletDir: File
        get() = File(context.filesDir, WALLET_DIR).apply { mkdirs() }

    private val manifestFile: File
        get() = File(walletDir, MANIFEST_NAME)

    private val tmpDir: File
        get() = File(context.cacheDir, TMP_DIR)

    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun encrypted(file: File): EncryptedFile =
        EncryptedFile.Builder(
            context,
            file,
            masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB,
        ).build()

    override suspend fun list(): List<WalletDoc> = withContext(Dispatchers.IO) {
        readManifest().sortedByDescending { it.capturedAtEpochMs }
    }

    override suspend fun save(
        type: WalletDocType,
        title: String,
        note: String,
        pageUris: List<String>,
    ): WalletDoc = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val docDir = File(walletDir, id).apply { mkdirs() }
        var pages = 0
        pageUris.forEach { uriString ->
            val bytes = runCatching {
                context.contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            }.getOrNull() ?: return@forEach
            val pageFile = File(docDir, "page_$pages.enc")
            if (pageFile.exists()) pageFile.delete()
            encrypted(pageFile).openFileOutput().use { it.write(bytes) }
            pages++
        }
        val doc = WalletDoc(
            id = id,
            type = type,
            title = title.ifBlank { defaultTitle(type) }.trim(),
            capturedAtEpochMs = System.currentTimeMillis(),
            pageCount = pages,
            note = note.trim(),
        )
        writeManifest(readManifest() + doc)
        doc
    }

    override suspend fun delete(docId: String): Unit = withContext(Dispatchers.IO) {
        File(walletDir, docId).deleteRecursively()
        writeManifest(readManifest().filterNot { it.id == docId })
    }

    override suspend fun materializePagesToCache(docId: String): List<String> =
        withContext(Dispatchers.IO) {
            val doc = readManifest().firstOrNull { it.id == docId } ?: return@withContext emptyList()
            val docDir = File(walletDir, docId)
            val outDir = File(tmpDir, docId).apply { mkdirs() }
            val uris = mutableListOf<String>()
            for (i in 0 until doc.pageCount) {
                val enc = File(docDir, "page_$i.enc")
                if (!enc.exists()) continue
                val bytes = runCatching {
                    encrypted(enc).openFileInput().use { it.readBytes() }
                }.getOrNull() ?: continue
                val out = File(outDir, "page_$i.jpg")
                out.writeBytes(bytes)
                uris += Uri.fromFile(out).toString()
            }
            uris
        }

    override suspend fun clearMaterialized(): Unit = withContext(Dispatchers.IO) {
        tmpDir.deleteRecursively()
    }

    private fun readManifest(): List<WalletDoc> {
        if (!manifestFile.exists()) return emptyList()
        return try {
            val text = encrypted(manifestFile).openFileInput().use { it.readBytes() }.decodeToString()
            json.decodeFromString<List<WalletDoc>>(text)
        } catch (e: Exception) {
            // MAC/keystore failure or corrupt manifest → treat as empty
            // (defense in depth; the dir is backup-excluded so this is rare).
            Log.w(TAG, "wallet manifest unreadable; treating as empty", e)
            emptyList()
        }
    }

    private fun writeManifest(docs: List<WalletDoc>) {
        if (manifestFile.exists()) manifestFile.delete() // EncryptedFile won't overwrite
        encrypted(manifestFile).openFileOutput().use {
            it.write(json.encodeToString(docs).encodeToByteArray())
        }
    }

    private fun defaultTitle(type: WalletDocType): String = when (type) {
        WalletDocType.ID -> "Photo ID"
        WalletDocType.PROOF_OF_ADDRESS -> "Proof of address"
        WalletDocType.PROOF_OF_INCOME -> "Proof of income"
        WalletDocType.AWARD_LETTER -> "Award letter"
        WalletDocType.BENEFIT_CARD -> "Benefit card"
        WalletDocType.OTHER -> "Document"
    }

    private companion object {
        const val WALLET_DIR = "wallet"
        const val MANIFEST_NAME = "manifest.enc"
        const val TMP_DIR = "wallet_tmp"
        const val TAG = "WalletStore"
    }
}
