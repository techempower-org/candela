package `in`.jphe.storyvox.sync.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Derives a user-bound AES-256 key for client-side encryption of secrets
 * before they leave the device.
 *
 * Threat model: InstantDB stores rows server-side. The user's auth tokens,
 * LLM API keys, and Royal Road cookies must never sit there as plaintext —
 * an Instant infra compromise (or any future migration to a different
 * backend) shouldn't disclose them. So secrets are AES-GCM-encrypted with a
 * key derived from a user-supplied passphrase via PBKDF2.
 *
 * Why not the refresh token: the refresh token rotates server-side, and if
 * the user signs in on a new device the new device gets a *different*
 * refresh token but should be able to decrypt the same secrets. Tying the
 * key to a stable passphrase is the only practical solution for end-to-end
 * encrypted secrets across devices.
 *
 * Caveat (documented in `docs/sync.md`): the passphrase doubles as your
 * recovery method. If you forget it, your encrypted secrets are gone — but
 * you still get to recover the *unencrypted* sync data (library, follows,
 * positions, etc.). This is by design; we trade some user-onboarding pain
 * for not having to trust the sync provider with our LLM API keys.
 *
 * Implementation notes:
 *  - PBKDF2WithHmacSHA256, 600k iterations, 32-byte key, 16-byte salt.
 *    600k matches NIST SP 800-132 + OWASP 2024 guidance — the upgrade
 *    landed in PR #360 (argus review finding 4) after the original 100k
 *    floor was flagged as below the modern minimum. Argus measured 600k
 *    at ~700ms on a Galaxy S8 (API 26 floor), well inside acceptable
 *    for a once-per-sync-startup KDF.
 *  - Output: a self-describing blob with `v2:<iv-b64>:<ct-b64>`.
 *    Format-versioned so a future move to Argon2id or AES-SIV can
 *    co-exist with older blobs. The salt slot was dropped in v2 (PR
 *    #360, argus finding 5) — see [envelope] for the design note.
 *    v1 envelopes (`v1:salt:iv:ct`) are still readable on the
 *    decode-side so any in-flight pre-fix blob can still be decrypted.
 */
object UserDerivedKey {

    private const val KDF_ALG = "PBKDF2WithHmacSHA256"
    /**
     * NIST SP 800-132 / OWASP 2024 minimum for PBKDF2-HMAC-SHA256.
     *
     * Issue #360 finding 4 (argus): the previous 100k was below the
     * modern floor — the kdoc justified it with API 26 phone latency,
     * but argus's measurement (Galaxy S8 → ~700ms at 600k) shows the
     * latency budget is fine. The KDF only runs once per sync-startup
     * and once per passphrase change, so a 700ms cost is invisible to
     * the user.
     */
    private const val KDF_ITERATIONS = 600_000
    private const val KDF_KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val ENVELOPE_VERSION = "v2"
    private const val LEGACY_V1 = "v1"

    /** Derive a 32-byte AES key from the passphrase + salt. Deterministic. */
    fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKey {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        val spec = PBEKeySpec(passphrase, salt, KDF_ITERATIONS, KDF_KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALG)
            val raw = factory.generateSecret(spec).encoded
            SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * Encrypt [plaintext] with the given [key]. Generates a fresh IV every
     * time — never reuse an IV with the same key under AES-GCM.
     */
    fun encrypt(key: SecretKey, plaintext: ByteArray): EncryptedBlob {
        val iv = randomBytes(IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext)
        return EncryptedBlob(iv = iv, ciphertext = ct)
    }

    /** Decrypt a blob previously produced by [encrypt]. */
    fun decrypt(key: SecretKey, blob: EncryptedBlob): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
        return cipher.doFinal(blob.ciphertext)
    }

    /**
     * Build a self-describing envelope string for storage.
     *
     * Format: `v2:<base64(iv)>:<base64(ct)>`
     *
     * **Why no salt in the envelope?** Issue #360 finding 5 (argus): in
     * v1 we generated a fresh per-encrypt salt and stored it in the
     * envelope, but the AES key was derived from a *different*,
     * deterministic-per-userId salt (`SHA-256("storyvox-secrets:" +
     * userId)[0..16]` in [SecretsSyncer]). The envelope salt was
     * unread on decrypt — cross-device decrypt worked only because
     * both devices independently recomputed the deterministic salt.
     *
     * The design constraint is "same user on a new device must derive
     * the same key from the same passphrase." A real per-encrypt
     * random salt would make that impossible without an extra
     * KDF-per-blob on every pull (~700ms at 600k iterations, × N
     * blobs — unacceptable for cold-start sync). So the deterministic
     * salt IS the design; the v2 envelope drops the misleading slot.
     *
     * Threat-model note: the deterministic salt + low-entropy
     * passphrase combination is offline-crackable if the rows are
     * exfiltrated. Mitigations: (1) PBKDF2 iterations bumped to 600k
     * (finding 4), (2) users should pick a high-entropy passphrase —
     * the UI tells them this. A per-user random salt stored
     * server-side under a separate path is a documented v2 follow-up.
     */
    fun envelope(blob: EncryptedBlob): String {
        val encoder = Base64.getEncoder().withoutPadding()
        val ivB64 = encoder.encodeToString(blob.iv)
        val ctB64 = encoder.encodeToString(blob.ciphertext)
        return "$ENVELOPE_VERSION:$ivB64:$ctB64"
    }

    /** Parse a [envelope] string. Returns null on any structural
     *  mismatch. Reads both v2 (`v2:iv:ct`) and legacy v1
     *  (`v1:salt:iv:ct`, salt ignored) for read-side back-compat with
     *  any in-flight pre-fix blob — caller doesn't see the salt slot
     *  either way. */
    fun parseEnvelope(envelope: String): ParsedEnvelope? {
        val parts = envelope.split(':')
        val decoder = Base64.getDecoder()
        return runCatching {
            when {
                parts.size == 3 && parts[0] == ENVELOPE_VERSION -> ParsedEnvelope(
                    blob = EncryptedBlob(
                        iv = decoder.decode(parts[1]),
                        ciphertext = decoder.decode(parts[2]),
                    ),
                )
                parts.size == 4 && parts[0] == LEGACY_V1 -> ParsedEnvelope(
                    blob = EncryptedBlob(
                        iv = decoder.decode(parts[2]),
                        ciphertext = decoder.decode(parts[3]),
                    ),
                )
                else -> null
            }
        }.getOrNull()
    }

    /** Generate a fresh random salt. */
    fun randomSalt(): ByteArray = randomBytes(SALT_BYTES)

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }

    data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EncryptedBlob) return false
            return iv.contentEquals(other.iv) && ciphertext.contentEquals(other.ciphertext)
        }
        override fun hashCode(): Int = iv.contentHashCode() * 31 + ciphertext.contentHashCode()
    }

    /** Issue #360 finding 5: the `salt` field was dropped (it had no
     *  cryptographic role — the actual key salt is deterministic
     *  per-userId, computed in [SecretsSyncer]). */
    data class ParsedEnvelope(val blob: EncryptedBlob)
}
