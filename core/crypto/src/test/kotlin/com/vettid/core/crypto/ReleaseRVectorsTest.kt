// Vector checks stay on one line each.
@file:Suppress("MaxLineLength")

package com.vettid.core.crypto

import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.Passwords
import com.vettid.core.crypto.altchan.RecoveryOpen
import com.vettid.core.crypto.altchan.UnlockSecret
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * VAULT-MESSAGING 0.33.0 §11.14 (release R), the app's side, against vettid-vault's `unlock.json` and
 * `recovery.json`: the phone-held unlock secret per epoch, the recovery `secrets` string with NFC, the recovery
 * open's signing string, and the unlock signing string's last line.
 */
class ReleaseRVectorsTest {
    private val unlock = VectorDoc.load("unlock.json")
    private val rr = VectorDoc.load("recovery.json").sub("release_r")

    private fun hmac(key: ByteArray): (ByteArray) -> ByteArray = { m -> Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(m) }

    @Test
    fun theUnlockSecretOfEachEpoch() {
        val phone = unlock.sub("phone")
        val key = phone.hex("phone_key_hex")
        assertBytes("epoch 1", phone.hex("epoch_1_hex"), UnlockSecret.compute(unlock.str("vault_id"), 1, hmac(key)))
        assertBytes("epoch 2", phone.hex("epoch_2_hex"), UnlockSecret.compute(unlock.str("vault_id"), 2, hmac(key)))
        assertThrows(CryptoException::class.java) { UnlockSecret.message(unlock.str("vault_id"), 0) }
    }

    @Test
    fun theRecoverySecretsString() {
        val s = Passwords.recoverySecrets(rr.str("pin"), rr.str("password"))
        assertBytes("secrets", rr.hex("secrets_hex"), s)
        assertEquals(rr.str("secrets_sha256_hex"), Bytes.hex(Bytes.sha256(s)))
    }

    @Test
    fun passwordsAreNormalizedToNfc() {
        // "café" typed with a combining accent (NFD) is the same password as with the precomposed letter.
        val nfd = "café horse battery"
        val nfc = "café horse battery"
        assertBytes("same secrets", Passwords.recoverySecrets("123456", nfc), Passwords.recoverySecrets("123456", nfd))
        assertEquals(nfc, Passwords.nfc(nfd))
    }

    @Test
    fun theRecoveryOpenSigningString() {
        val f = RecoveryOpen.Fields(
            userGuid = "test-user-guid", vaultId = rr.str("vault_id"), requestId = "01JB2Z6V9K3M4N5P6Q7R8S9T31", recoveryId = rr.str("recovery_id"),
            ts = "2026-10-15T12:00:00.000Z", etkKid = Kid.parseHex("40d6c2c8471844a9"),
            secrets = Passwords.recoverySecrets(rr.str("pin"), rr.str("password")), token = "test-token",
            manifestSha256 = Bytes.hex(Bytes.sha256("manifest".toByteArray())), unlockSecret = rep(0x42, 32),
        )
        assertEquals(rr.str("open_signing_string"), RecoveryOpen.signingString(f))
    }

    @Test
    fun theUnlockSigningStringEndsWithTheSecretsHash() {
        fun fields(cancel: Boolean = false, secret: ByteArray? = null) = AltChannel.UnlockFields(
            userGuid = "test-user-guid", vaultId = "test-vault-0001", requestId = "01JB2Z6V9K3M4N5P6Q7R8S9T31", ts = "2026-10-15T12:00:00.000Z",
            etkKid = Kid.parseHex("40d6c2c8471844a9"), minStateSeq = 3, minHeaderSeq = 4, pin = "123456", token = "test-token",
            manifestSha256 = Bytes.hex(Bytes.sha256("manifest".toByteArray())), cancelRecovery = cancel, unlockSecret = secret,
        )
        val base = fields()
        val before = AltChannel.unlockSigningString(base)
        val secret = unlock.hex("unlock_secret_hex")
        val r = AltChannel.unlockSigningString(fields(secret = secret))
        assertEquals(before + "\n" + Bytes.hex(Bytes.sha256(secret)), r)
        val withCancel = AltChannel.unlockSigningString(fields(cancel = true, secret = secret))
        assertTrue("after cancel_recovery", withCancel.endsWith("\ncancel_recovery\n" + Bytes.hex(Bytes.sha256(secret))))
        assertThrows(CryptoException::class.java) { AltChannel.unlockSigningString(fields(secret = ByteArray(31))) }
    }

    @Test
    fun aRecoveryOpenResultIsRead() {
        val wrong = RecoveryOpen.Result.parse("""{"ok":false,"code":"wrong","attempts":{"used":3,"of":10},"retry_at":"2026-10-17T12:00:00Z"}""".toByteArray())
        assertEquals("wrong", wrong.code)
        assertTrue(wrong.counted)
        assertEquals(3, wrong.used)
        assertEquals(java.time.Instant.parse("2026-10-17T12:00:00Z"), wrong.retryAt)
        val busy = RecoveryOpen.Result.parse("""{"ok":false,"code":"busy"}""".toByteArray())
        assertTrue(!busy.counted && busy.retryAt == null)
        val ok = RecoveryOpen.Result.parse("""{"ok":true,"state_seq":5,"header_seq":6,"token":"t","vault_bundle":"AAEC","release":"aa","release_number":13,"unlock_id":"00","attempts":{"used":1,"of":10}}""".toByteArray())
        assertTrue(ok.ok)
        assertEquals(1, ok.used)
    }
}
