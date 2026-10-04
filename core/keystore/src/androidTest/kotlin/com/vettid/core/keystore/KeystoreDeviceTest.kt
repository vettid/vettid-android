package com.vettid.core.keystore

import android.security.keystore.UserNotAuthenticatedException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.attestation.android.KeyDescription
import com.vettid.core.attestation.android.SecurityLevel
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.DeviceAssertion
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Signature
import javax.crypto.Cipher

/**
 * Keystore behaviour on a real device (phase A1). Runs unattended on a locked
 * test phone: nothing here needs a biometric. The biometric success path of
 * [AppDataKey] needs a person at the phone and is exercised by the A3 app-lock
 * flow.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val testAlias = "vettid.test.device_attestation"

    @After
    fun cleanup() {
        listOf(testAlias, "vettid.test.seed_wrap", "vettid.test.app_data").forEach { AndroidKeys.delete(it) }
    }

    @Test
    fun deviceAttestationKeyCarriesTheChallenge() {
        // The §11.7 challenge of an enrollment request (vault_id empty).
        val challenge = AltChannel.devattChallenge("01JB2Z6V9K3M4N5P6Q7R8S9T20", "", "2026-10-01T12:00:00.000Z")
        val key = DeviceAttestationKey(testAlias)
        val attested = key.generate(challenge)
        val strongBox = AndroidKeys.hasStrongBox(context.packageManager)
        Log.i(TAG, "attestation key: level=${attested.level} strongBox=${attested.strongBox} feature=$strongBox chain=${attested.chain.size}")
        assertTrue(attested.chain.size in 2..10)
        if (strongBox) assertEquals(KeyLevel.STRONG_BOX, attested.level) else assertEquals(KeyLevel.TEE, attested.level)

        // The chain is internally consistent (the enclave checks it up to Google's root).
        for (i in 0 until attested.chain.size - 1) attested.chain[i].verify(attested.chain[i + 1].publicKey)

        val kd = KeyDescription.parse(attested.chain[0])
        Log.i(
            TAG,
            "KeyDescription: version=${kd.attestationVersion} att=${kd.attestationSecurityLevel} km=${kd.keyMintSecurityLevel} " +
                "purposes=${kd.purposes} alg=${kd.algorithm} curve=${kd.ecCurve} origin=${kd.origin} " +
                "locked=${kd.rootOfTrust?.deviceLocked} boot=${kd.rootOfTrust?.verifiedBootState} " +
                "bootKey=${kd.rootOfTrust?.verifiedBootKey()?.let { Bytes.hex(it) }} problems=${kd.problems(challenge)}",
        )
        assertArrayEquals(challenge, kd.challenge())
        assertNotEquals(SecurityLevel.SOFTWARE, kd.attestationSecurityLevel)
        assertNotEquals(SecurityLevel.SOFTWARE, kd.keyMintSecurityLevel)
        if (strongBox) assertEquals(SecurityLevel.STRONG_BOX, kd.keyMintSecurityLevel)
        assertTrue(kd.attestationVersion >= KeyDescription.MIN_ATTESTATION_VERSION)
        assertEquals(setOf(KeyDescription.SIGN), kd.purposes)
        assertEquals(KeyDescription.ALGORITHM_EC, kd.algorithm)
        assertEquals(KeyDescription.CURVE_P256, kd.ecCurve)
        assertEquals(KeyDescription.ORIGIN_GENERATED, kd.origin)

        // An unlock assertion: SHA256withECDSA (DER) over the 32 challenge bytes.
        val unlockChallenge = AltChannel.devattChallenge("01JB2Z6V9K3M4N5P6Q7R8S9T21", "test-vault-0001", "2026-10-01T12:00:00.000Z")
        val sig = key.sign(unlockChallenge)
        val ok = Signature.getInstance("SHA256withECDSA").run {
            initVerify(attested.chain[0].publicKey)
            update(unlockChallenge)
            verify(sig)
        }
        assertTrue(ok)
        assertTrue(DeviceAssertion.android(sig).marshal().startsWith("{\"platform\":\"android\",\"sig\":\""))
        assertTrue(KeyDescription.deviceAttest(attested.chain).marshal().contains("\"chain\":[\""))

        // A new attestation is a new key.
        val again = key.generate(challenge)
        assertNotEquals(Bytes.hex(attested.chain[0].publicKey.encoded), Bytes.hex(again.chain[0].publicKey.encoded))
        key.delete()
        assertTrue(!key.exists())
    }

    @Test
    fun seedsAreWrappedByTheKeystore() {
        val wrapper = SeedWrapKey.wrapper("vettid.test.seed_wrap")
        val level = SeedWrapKey.level("vettid.test.seed_wrap")
        Log.i(TAG, "seed wrap key level=$level")
        assertTrue(level == KeyLevel.TEE || level == KeyLevel.STRONG_BOX)
        val store = SharedPreferencesWrappedKeyStore(context)
        val keys = DeviceKeys(wrapper, store)
        try {
            KeySlot.entries.forEach { keys.generate(it) }
            val ik = keys.ed25519(KeySlot.IDENTITY)
            val again = keys.ed25519(KeySlot.IDENTITY)
            assertArrayEquals(ik.publicKey, again.publicKey)
            assertEquals(keys.kem().publicKey, keys.kem().publicKey)
            val blob = store.get(KeySlot.IDENTITY)!!
            assertThrows(KeystoreException::class.java) { wrapper.unwrap(KeySlot.RELAY, blob) }
        } finally {
            keys.clear()
        }
    }

    @Test
    fun appDataKeyRequiresAuthentication() {
        val key = AppDataKey("vettid.test.app_data")
        // With a validity window: unusable until the member authenticates.
        val level = key.create(authTimeoutSeconds = 10)
        Log.i(TAG, "app-data key level=$level")
        assertTrue(level == KeyLevel.TEE || level == KeyLevel.STRONG_BOX)
        assertThrows(UserNotAuthenticatedException::class.java) { key.encryptCipher() }
        // Per-use: the cipher initialises (for BiometricPrompt.CryptoObject) but cannot run unauthenticated.
        key.create(authTimeoutSeconds = 0)
        val c: Cipher = key.encryptCipher()
        try {
            c.doFinal(ByteArray(32))
            fail("an auth-per-use key encrypted without authentication")
        } catch (e: Exception) {
            Log.i(TAG, "per-use key refused as expected: ${e.javaClass.simpleName}")
        }
        key.delete()
    }

    private companion object {
        const val TAG = "VettIdKeystoreTest"
    }
}
