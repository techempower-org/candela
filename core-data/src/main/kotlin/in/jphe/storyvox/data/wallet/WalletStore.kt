package `in`.jphe.storyvox.data.wallet

import kotlinx.serialization.Serializable

/**
 * Issue #1514 — "My Documents": an encrypted, on-device wallet for
 * benefits proofs (ID, pay stubs, award letters, benefit cards…).
 *
 * ## Why a store seam
 *
 * The production impl (`EncryptedFileWalletStore`) persists everything —
 * both the scan images AND the metadata manifest — with Jetpack Security
 * `EncryptedFile` in an app-private `files/wallet/` dir that is EXCLUDED
 * from cloud backup + device transfer and never enters `core-sync`. This
 * is the household's most sensitive data, so nothing is stored in the
 * clear and nothing leaves the device.
 *
 * Persisting behind this interface keeps the choice reversible (Room +
 * SQLCipher could replace it) and keeps [`in`.jphe.storyvox.data.wallet]
 * unit-testable: the ViewModel's logic (staleness, "what does this
 * prove?") is exercised against a fake store with no encryption / no
 * Android file I/O.
 */
interface WalletStore {

    /** All stored documents, newest first. Returns empty (never throws)
     *  if the manifest is absent or can't be decrypted (defense in depth —
     *  a restored-but-unverifiable blob is treated as absent, mirroring
     *  the #951 EncryptedSharedPreferences recovery). */
    suspend fun list(): List<WalletDoc>

    /**
     * Encrypt and store [pageUris]' bytes as a new document. [pageUris]
     * are `content://`/`file://` image URIs (from the scanner / gallery);
     * the impl reads them through the ContentResolver and writes each page
     * as an EncryptedFile. Returns the created [WalletDoc].
     */
    suspend fun save(
        type: WalletDocType,
        title: String,
        note: String,
        pageUris: List<String>,
    ): WalletDoc

    /** Delete a document and its encrypted pages. */
    suspend fun delete(docId: String)

    /**
     * Decrypt a document's pages to short-lived files under the cache dir
     * and return their `file://` URIs, so the caller can compose a
     * shareable PDF via the existing `DocPdfExporter`. The caller MUST
     * call [clearMaterialized] once the export completes so decrypted
     * bytes don't linger. Returns empty if the doc is missing.
     */
    suspend fun materializePagesToCache(docId: String): List<String>

    /** Delete any files produced by [materializePagesToCache]. */
    suspend fun clearMaterialized()
}

/**
 * The five things every application asks for, plus "other". [proofNoun]
 * is a short human label; [stalenessDays] is the freshness window after
 * which some programs reject the proof (null = no staleness rule).
 */
@Serializable
enum class WalletDocType(val stalenessDays: Int?) {
    /** Photo ID (driver license, state ID, passport). */
    ID(null),

    /** Proof of address (utility bill, lease). */
    PROOF_OF_ADDRESS(null),

    /**
     * Proof of income — gross pay stubs. Staleness bites here: e.g. the
     * CASF Line Extension Program accepts only pay stubs from the last
     * 45 days (a real rejection documented on techempower.org).
     */
    PROOF_OF_INCOME(45),

    /** Benefit award / eligibility letter (NOA). */
    AWARD_LETTER(null),

    /** A benefit card (Medi-Cal, CalFresh EBT, CalWORKs). */
    BENEFIT_CARD(null),

    /** Anything else (vehicle registration, etc.). */
    OTHER(null),
}

/**
 * One stored document's metadata. Page bytes live in separate
 * EncryptedFiles keyed by [id]; this record is the encrypted manifest
 * entry. [capturedAtEpochMs] is wall-clock capture time (for staleness).
 */
@Serializable
data class WalletDoc(
    val id: String,
    val type: WalletDocType,
    val title: String,
    val capturedAtEpochMs: Long,
    val pageCount: Int,
    val note: String = "",
) {
    /**
     * True when this document's type carries a staleness window and it
     * has been exceeded as of [nowEpochMs]. Pure — unit-tested without
     * the encrypted store.
     */
    fun isStale(nowEpochMs: Long): Boolean {
        val days = type.stalenessDays ?: return false
        val ageMs = nowEpochMs - capturedAtEpochMs
        return ageMs > days.toLong() * MILLIS_PER_DAY
    }

    /** Whole days since capture (for the "captured N days ago" hint). */
    fun ageDays(nowEpochMs: Long): Long =
        ((nowEpochMs - capturedAtEpochMs).coerceAtLeast(0L)) / MILLIS_PER_DAY

    companion object {
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
    }
}
