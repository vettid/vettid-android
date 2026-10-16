package com.vettid.core.data.vault

import com.vettid.core.crypto.Bytes
import com.vettid.core.keystore.KeyLevel
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.PhoneKeyHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The phone key's secrets per epoch (VAULT-MESSAGING 0.33.0 §11.14.1, vettid-vault `unlock.json`'s phone vector). */
class PhoneSecretsTest {
    private class SoftKey(private val level: KeyLevel = KeyLevel.TEE) : PhoneKeyHandle {
        var key: ByteArray? = null

        override fun create(): KeyLevel {
            if (level == KeyLevel.SOFTWARE) throw KeystoreException("phone key in software")
            key = ByteArray(32) { 0x44 }
            return level
        }

        override fun exists() = key != null

        override fun delete() {
            key = null
        }

        override fun hmac(message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key!!, "HmacSHA256")) }.doFinal(message)
    }

    private val keys = mutableMapOf<String, SoftKey>()
    private val secrets = PhoneSecrets { keys.getOrPut(it) { SoftKey() } }

    @Test
    fun epochOneAndTheNextEpochMatchTheVector() {
        val s = secrets.create("test-vault-0001")
        assertEquals(PhoneSecretState("test-vault-0001", 1), s)
        assertArrayEquals(Bytes.unhex("84ae0b66319d60524f6dcffa6fa3c540669caa0f13a2b1cc01c49305d4af7836"), secrets.secret(s))
        assertArrayEquals(Bytes.unhex("03bbaea3348d1fdb182a3474faf655f7f444c74be38ab84423ec21f3a2587f07"), secrets.secret(s, ahead = 1))
        assertArrayEquals(secrets.secret(s, ahead = 1), secrets.secret(s.copy(epoch = 2)))
    }

    @Test
    fun aMissingKeyIsThePhoneNoLongerHoldingTheKeys() {
        val s = secrets.create("v1")
        secrets.delete(s)
        assertEquals(FailureKind.DEVICE_SECRET, assertThrows(VaultFailure::class.java) { secrets.secret(s) }.kind)
        assertEquals(FailureKind.DEVICE_SECRET, assertThrows(VaultFailure::class.java) { secrets.secret(PhoneSecretState()) }.kind)
        assertFalse(PhoneSecretState().inUse)
        assertTrue(s.inUse)
    }

    @Test
    fun aKeyInSoftwareIsRefused() {
        val soft = PhoneSecrets { SoftKey(KeyLevel.SOFTWARE) }
        assertEquals(FailureKind.HARDWARE_KEYS, assertThrows(VaultFailure::class.java) { soft.create("v1") }.kind)
    }

    /** A retry of an enrollment or a transfer reuses the key the vault may already hold the secret of. */
    @Test
    fun aRetryReusesTheKeyOfEpochOne() {
        val first = secrets.create("v1")
        val before = secrets.secret(first)
        val again = secrets.reuseOrCreate(first, "v1")
        assertEquals(first, again)
        assertArrayEquals(before, secrets.secret(again))
        // Another vault, a later epoch, or a key that is gone: a new key.
        assertEquals(PhoneSecretState("v2", 1), secrets.reuseOrCreate(first, "v2"))
        val k = keys.getValue("v1")
        val later = secrets.reuseOrCreate(first.copy(epoch = 2), "v1")
        assertEquals(PhoneSecretState("v1", 1), later)
        k.delete()
        assertTrue(secrets.reuseOrCreate(later, "v1").inUse)
        assertTrue(k.exists())
        // Nothing yet: a new key.
        assertEquals(PhoneSecretState("v3", 1), secrets.reuseOrCreate(PhoneSecretState(), "v3"))
    }

    /** Only answers the vault gives after the secret matched move the phone to the next epoch. */
    @Test
    fun theNextEpochIsKeptOnlyWhenTheSecretWasAccepted() {
        assertTrue(UnlockEpoch.confirmsNext(ok = true, code = null))
        assertTrue(UnlockEpoch.confirmsNext(ok = false, code = "bad_pin"))
        assertTrue(UnlockEpoch.confirmsNext(ok = false, code = "transfer_incomplete"))
        for (c in listOf("device_secret", "backoff", "manifest", "retry", "attestation", "recovery_pending", null)) {
            assertFalse(c ?: "null", UnlockEpoch.confirmsNext(ok = false, code = c))
        }
    }

    /** The old phone's 7 days after a transfer it approved. */
    @Test
    fun aKeptTransferEndsAfterSevenDays() {
        val day = 24L * 60 * 60 * 1000
        val at = 1_000_000_000_000L
        assertEquals(KeptTransfer.Decision.Keep(7 * day), KeptTransfer.decide(at, at))
        assertEquals(KeptTransfer.Decision.Keep(day), KeptTransfer.decide(at, at + 6 * day))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(at, at + 7 * day))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(at, at + 30 * day))
        // A clock set back before the approval never gives more than 7 days from now.
        assertEquals(KeptTransfer.Decision.Keep(7 * day), KeptTransfer.decide(at, at - 3 * day))
    }

    /** §11.14.1: only the trusted execution environment and StrongBox hold a phone key. */
    @Test
    fun onlySecureHardwareHoldsThePhoneKey() {
        assertTrue(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.TEE))
        assertTrue(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.STRONG_BOX))
        assertFalse(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.SOFTWARE))
        assertFalse(com.vettid.core.keystore.PhoneKey.holdsPhoneKey(KeyLevel.UNKNOWN))
    }
}
