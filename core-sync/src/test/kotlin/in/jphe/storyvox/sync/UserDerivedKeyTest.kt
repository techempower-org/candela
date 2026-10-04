package `in`.jphe.storyvox.sync

import `in`.jphe.storyvox.sync.crypto.UserDerivedKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UserDerivedKeyTest {

    @Test fun `deriveKey is deterministic for the same passphrase and salt`() {
        val salt = UserDerivedKey.randomSalt()
        val a = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val b = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        assertTrue("same passphrase + salt should produce same key bytes",
            a.encoded.contentEquals(b.encoded))
    }

    @Test fun `deriveKey is different for different salts`() {
        val passphrase = "hunter2".toCharArray()
        val a = UserDerivedKey.deriveKey(passphrase, UserDerivedKey.randomSalt())
        val b = UserDerivedKey.deriveKey(passphrase, UserDerivedKey.randomSalt())
        assertFalse(a.encoded.contentEquals(b.encoded))
    }

    @Test fun `encrypt then decrypt roundtrips the plaintext`() {
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val plaintext = "{\"openai\":\"sk-test\",\"royalroad\":\"cookie-blob\"}".toByteArray()
        val ct = UserDerivedKey.encrypt(key, plaintext)
        val pt = UserDerivedKey.decrypt(key, ct)
        assertTrue(plaintext.contentEquals(pt))
    }

    @Test fun `wrong key fails to decrypt`() {
        val saltA = UserDerivedKey.randomSalt()
        val saltB = UserDerivedKey.randomSalt()
        val keyA = UserDerivedKey.deriveKey("hunter2".toCharArray(), saltA)
        val keyB = UserDerivedKey.deriveKey("hunter3".toCharArray(), saltB)
        val ct = UserDerivedKey.encrypt(keyA, "secret".toByteArray())
        try {
            UserDerivedKey.decrypt(keyB, ct)
            fail("expected GCM auth failure")
        } catch (e: Exception) {
            // GCM throws AEADBadTagException (subclass of BadPaddingException);
            // either is acceptable evidence of authenticated-decrypt failure.
        }
    }

    @Test fun `v2 envelope roundtrips through parse`() {
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val ct = UserDerivedKey.encrypt(key, "secret".toByteArray())
        val env = UserDerivedKey.envelope(ct)
        // Issue #360 finding 5: the v2 envelope no longer carries a
        // salt slot — the salt is deterministic per-userId, computed
        // in SecretsSyncer, not stored alongside the ciphertext.
        assertTrue("v2 envelope must start with v2:", env.startsWith("v2:"))
        val parsed = UserDerivedKey.parseEnvelope(env)
        assertNotNull(parsed)
        assertEquals(ct, parsed!!.blob)
        // And the parsed blob decrypts cleanly with the original key.
        assertTrue("secret".toByteArray().contentEquals(UserDerivedKey.decrypt(key, parsed.blob)))
    }

    @Test fun `parseEnvelope returns null on garbage`() {
        assertNull(UserDerivedKey.parseEnvelope(""))
        assertNull(UserDerivedKey.parseEnvelope("v0:foo:bar")) // bad version
        assertNull(UserDerivedKey.parseEnvelope("v2:notbase64!!:notbase64!!"))
        assertNull(UserDerivedKey.parseEnvelope("v2:onlyoneparts"))
        // A v1-shaped envelope with the wrong field count is rejected.
        assertNull(UserDerivedKey.parseEnvelope("v1:onlytwo:fields"))
    }

    @Test fun `legacy v1 envelope is still parseable (back-compat for in-flight blobs)`() {
        // PR #360 isn't merged yet, so no production v1 blobs exist —
        // but the read-side back-compat path is cheap to keep, and
        // protects internal devices that may have written v1 envelopes
        // during dogfooding from a one-time decrypt-fail on the next
        // sync after the v2 deploy. The salt slot is parsed but
        // ignored; the IV + ciphertext decode normally.
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val ct = UserDerivedKey.encrypt(key, "legacy-secret".toByteArray())
        // Hand-roll a v1 envelope using the now-private format.
        val encoder = java.util.Base64.getEncoder().withoutPadding()
        val legacyEnv = "v1:" +
            encoder.encodeToString(salt) + ":" +
            encoder.encodeToString(ct.iv) + ":" +
            encoder.encodeToString(ct.ciphertext)
        val parsed = UserDerivedKey.parseEnvelope(legacyEnv)
        assertNotNull("v1 envelope must remain parseable", parsed)
        assertTrue(
            "legacy-secret".toByteArray().contentEquals(UserDerivedKey.decrypt(key, parsed!!.blob)),
        )
    }

    @Test fun `encrypt produces a different iv each call`() {
        val salt = UserDerivedKey.randomSalt()
        val key = UserDerivedKey.deriveKey("hunter2".toCharArray(), salt)
        val plaintext = "same plaintext".toByteArray()
        val a = UserDerivedKey.encrypt(key, plaintext)
        val b = UserDerivedKey.encrypt(key, plaintext)
        // GCM nonce-reuse with the same key is catastrophic; verify we
        // generate fresh IVs and therefore the ciphertexts diverge.
        assertFalse(a.iv.contentEquals(b.iv))
        assertFalse(a.ciphertext.contentEquals(b.ciphertext))
    }
}
