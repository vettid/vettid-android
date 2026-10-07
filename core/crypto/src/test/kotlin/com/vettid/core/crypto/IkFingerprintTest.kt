package com.vettid.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * VAULT-MESSAGING 0.18.0 §10.8 / §16 "ik fingerprint": the fingerprint of the §16 vault ik (keys.json `vault`,
 * seed 32 x 0x04). vettid-vault adds the values to keys.json as `ik_fingerprint` (feat/profile-core-0.18.0); until
 * that copy lands here, the expected values are the §16 excerpt's.
 */
class IkFingerprintTest {
    private val ik = VectorDoc.load("keys.json").sub("vault").b64("ik_pk_b64")

    @Test
    fun specVector() {
        assertEquals("ypOsFwUYcHHWe4PH/w7+gQjo7EUwV113JoeTM9vavnw=", Base64s.encodeStd(ik))
        assertEquals(
            "9a1fbb7d873eeafb494bef94f0727b2539c2faf27783d46d4e6862673f6216c3",
            Bytes.hex(IkFingerprint.digest(ik)),
        )
        assertEquals("9a1f bb7d 873e eafb 494b ef94 f072 7b25", IkFingerprint.format(ik))
        assertEquals("9a1f bb7d 873e eafb 494b ef94 f072 7b25", IkFingerprint.formatB64(Base64s.encodeStd(ik)))
    }

    @Test
    fun labelled() {
        // Not the plain hash of the key: the label comes first.
        assertEquals(false, Bytes.hex(Bytes.sha256(ik)).startsWith("9a1fbb7d"))
    }

    @Test
    fun onlyThirtyTwoBytes() {
        assertThrows(CryptoException::class.java) { IkFingerprint.format(ByteArray(31)) }
        assertNull(IkFingerprint.formatB64(""))
        assertNull(IkFingerprint.formatB64("not base64!"))
        assertNull(IkFingerprint.formatB64(Base64s.encodeStd(ByteArray(33))))
    }
}
