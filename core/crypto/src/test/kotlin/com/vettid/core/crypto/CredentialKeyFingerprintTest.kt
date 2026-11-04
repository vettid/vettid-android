package com.vettid.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** VAULT-MESSAGING 0.83.8 §10.8 / §16 "ck fingerprint": the key is 32 x 0x09. */
class CredentialKeyFingerprintTest {
    private val key = ByteArray(32) { 0x09 }

    @Test
    fun specVector() {
        assertEquals("CQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQk=", Base64s.encodeStd(key))
        assertEquals(
            "e939754d09aaa93a6bd164b82df791506a5224bdd42a19e1dc023176ee6fe9b5",
            Bytes.hex(CredentialKeyFingerprint.digest(key)),
        )
        assertEquals("e939 754d 09aa a93a", CredentialKeyFingerprint.format(key))
        assertEquals("e939 754d 09aa a93a", CredentialKeyFingerprint.formatB64(Base64s.encodeStd(key)))
    }

    @Test
    fun labelled() {
        assertEquals(false, Bytes.hex(Bytes.sha256(key)).startsWith("e939754d"))
    }

    @Test
    fun onlyThirtyTwoBytes() {
        assertThrows(CryptoException::class.java) { CredentialKeyFingerprint.format(ByteArray(31)) }
        assertNull(CredentialKeyFingerprint.formatB64(""))
        assertNull(CredentialKeyFingerprint.formatB64("not base64!"))
        assertNull(CredentialKeyFingerprint.formatB64(Base64s.encodeStd(ByteArray(33))))
    }
}
