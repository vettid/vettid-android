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

    /** A clock behind the approval: the 7 days count from when that is noticed, and that time is kept. */
    @Test
    fun aClockBehindTheApprovalRestartsTheSevenDaysOnce() {
        val day = 24L * 60 * 60 * 1000
        val at = 1_000_000_000_000L
        assertEquals(at, KeptTransfer.start(at, at + day))
        val noticed = at - 3 * day
        assertEquals(noticed, KeptTransfer.start(at, noticed))
        // Later checks with the clock still behind the first approval count from the stored, noticed time.
        assertEquals(KeptTransfer.Decision.Keep(6 * day), KeptTransfer.decide(KeptTransfer.start(noticed, noticed + day), noticed + day))
        assertEquals(KeptTransfer.Decision.Erase, KeptTransfer.decide(KeptTransfer.start(noticed, noticed + 7 * day), noticed + 7 * day))
    }

    /** Only an approval that got no answer may have reached the vault; any other failure removes the stored time. */
    @Test
    fun onlyAnUnansweredApprovalKeepsItsStoredTime() {
        assertTrue(KeptTransfer.mayHaveReached(FailureKind.NO_RESPONSE))
        for (k in listOf(FailureKind.BAD_PIN, FailureKind.BAD_PASSWORD, FailureKind.BUSY, FailureKind.NETWORK, FailureKind.OTHER)) {
            assertFalse(k.name, KeptTransfer.mayHaveReached(k))
        }
    }

    /** "Finish the move" ends only with an unlock the vault answered ok and whose result this phone took. */
    @Test
    fun onlyAnUnlockThatOpenedTheVaultFinishesTheMove() {
        assertTrue(FinishMoveProof.provedBy(resultOk = true, overtaken = false))
        assertFalse(FinishMoveProof.provedBy(resultOk = true, overtaken = true))
        assertFalse(FinishMoveProof.provedBy(resultOk = false, overtaken = false))
    }

    /** Release R: `exists` on credential.create fetches the credential the vault holds; before release R it stays an error. */
    @Test
    fun anExistingCredentialIsFetchedOnlyForReleaseR() {
        assertTrue(CredentialCreate.fetchInstead(releaseR = true, code = "exists"))
        assertFalse(CredentialCreate.fetchInstead(releaseR = false, code = "exists"))
        assertFalse(CredentialCreate.fetchInstead(releaseR = true, code = "weak_password"))
    }

    /**
     * The one check at the end of the 7 days: kept only when the vault's device.list over this phone's session lists it
     * as the app; unlinked (not listed), any other answer, no answer or a failure (null) erase.
     */
    @Test
    fun theEndOfKeepCheckKeepsOnlyWhenTheVaultListsThisPhoneAsItsApp() {
        fun list(vararg d: Pair<String, String>) = kotlinx.serialization.json.Json.parseToJsonElement(
            d.joinToString(",", "{\"devices\":[", "]}") { (id, kind) -> "{\"id\":\"$id\",\"kind\":\"$kind\",\"name\":\"x\"}" },
        ) as kotlinx.serialization.json.JsonObject
        // Confirmed: kept.
        assertTrue(KeptTransfer.stillTheApp(list("dev-old" to "app", "desk-1" to "desktop"), "dev-old"))
        // Unlinked: the vault's app is another phone.
        assertFalse(KeptTransfer.stillTheApp(list("dev-new" to "app", "desk-1" to "desktop"), "dev-old"))
        // Listed, but not as the app.
        assertFalse(KeptTransfer.stillTheApp(list("dev-old" to "desktop"), "dev-old"))
        // No answer, a refusal, a failure, or a phone without a device id.
        assertFalse(KeptTransfer.stillTheApp(null, "dev-old"))
        assertFalse(KeptTransfer.stillTheApp(list("dev-old" to "app"), null))
        assertFalse(KeptTransfer.stillTheApp(kotlinx.serialization.json.JsonObject(emptyMap()), "dev-old"))
    }

    /** §11.10.9: the path from the verified capabilities, and the mark that never lets a vault go back. */
    @Test
    fun theCapabilitiesChooseThePathAndAMarkedVaultNeverGoesBack() {
        // A vault whose release lists nothing: the earlier path, exactly as before.
        assertEquals(ReleasePath.Path.EARLIER, ReleasePath.choose(emptyList(), releaseRKnown = false))
        assertEquals(ReleasePath.Path.EARLIER, ReleasePath.choose(listOf("attachments"), releaseRKnown = false))
        // recovery_r: release R's requests.
        assertEquals(ReleasePath.Path.R, ReleasePath.choose(listOf("recovery_r"), releaseRKnown = false))
        assertEquals(ReleasePath.Path.R, ReleasePath.choose(listOf("recovery_offered", "recovery_r"), releaseRKnown = true))
        // Seen release R, then an instance without it: refused like a rollback (nothing is built or sent).
        val e = assertThrows(VaultFailure::class.java) { ReleasePath.choose(listOf("attachments"), releaseRKnown = true) }
        assertEquals(FailureKind.CAPABILITY_ROLLBACK, e.kind)
        assertEquals(ReleasePath.CODE_CAPABILITY_ROLLBACK, e.code)
        assertThrows(VaultFailure::class.java) { ReleasePath.choose(emptyList(), releaseRKnown = true) }
    }

    /** Recovery is shown for a release before R (its earlier recovery), and from release R only with recovery_offered. */
    @Test
    fun recoveryIsOfferedOnlyWhenTheReleaseSaysSo() {
        assertTrue(ReleasePath.recoveryOffered(emptyList()))
        assertTrue(ReleasePath.recoveryOffered(listOf("recovery_offered", "recovery_r")))
        assertFalse(ReleasePath.recoveryOffered(listOf("attachments", "recovery_r")))
    }

    /**
     * The mark: set by an R path that makes the phone key (enrollment, a transfer to this phone, a register) or on a phone
     * that holds a phone secret; an unlock of a phone without one that meets an R instance never sets it.
     */
    @Test
    fun anUnlockWithoutAPhoneSecretNeverMarksTheVault() {
        val r = ReleasePath.Path.R
        assertFalse(ReleasePath.marks(wasMarked = false, path = r, makesPhoneKey = false, holdsPhoneSecret = false))
        assertTrue(ReleasePath.marks(wasMarked = false, path = r, makesPhoneKey = true, holdsPhoneSecret = false))
        assertTrue(ReleasePath.marks(wasMarked = false, path = r, makesPhoneKey = false, holdsPhoneSecret = true))
        assertFalse(ReleasePath.marks(wasMarked = false, path = ReleasePath.Path.EARLIER, makesPhoneKey = true, holdsPhoneSecret = true))
        assertTrue(ReleasePath.marks(wasMarked = true, path = ReleasePath.Path.EARLIER, makesPhoneKey = false, holdsPhoneSecret = false))
        // So after such an unlock the phone still reaches its own earlier vault.
        assertEquals(ReleasePath.Path.EARLIER, ReleasePath.choose(emptyList(), releaseRKnown = false))
    }
}
