package com.vettid.core.keystore

import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * DEBUG ONLY, run by hand on a connected phone (README "Signer binding"): captures a REAL key attestation chain,
 * for checking the vault's chain rules (VAULT-MESSAGING 0.27.0 §11.7, §11.7.1) against what phones produce. It
 * touches no vault and no key of the app.
 *
 * A throwaway key is generated exactly as the app's device key is ([DeviceAttestationKey.generate], the app's own
 * code: the same `KeyGenParameterSpec`, StrongBox where the phone has it, a 32-byte challenge, no attest-key
 * alias) under a test alias; its certificate chain is read from Android Keystore, then read again; the chain is
 * written as PEM, leaf first, to the test's output directory and to logcat (tag `VettIDChainDump`, between the
 * BEGIN and END marker lines); the key is deleted.
 *
 * Asserted: the leaf is the only certificate with the KeyDescription extension (OID 1.3.6.1.4.1.11129.2.1.17),
 * and the second read is byte-identical to the first (the vault relies on Keystore returning later the chain it
 * returned at attestation).
 *
 * A certificate chain is public data (it goes to the enclave at every attestation). Nothing else of the phone is
 * logged or written.
 */
@RunWith(AndroidJUnit4::class)
class AttestationChainDumpTest {
    private val alias = "vettid.test.chain_dump"

    @After
    fun cleanup() = AndroidKeys.delete(alias)

    @Test
    fun dumpsTheAttestationChainOfAKeyMadeAsTheDeviceKeyIs() {
        // 32 bytes, as §11.7's challenge is (a SHA-256); its value is fixed and means nothing.
        val challenge = MessageDigest.getInstance("SHA-256").digest("VettID chain dump (test only)".toByteArray())
        val key = DeviceAttestationKey(alias)
        val first = key.generate(challenge).chain
        assertTrue("1 to 10 certificates, got ${first.size}", first.size in 1..MAX_CHAIN)

        // The attestation extension is on the leaf and on no other certificate.
        assertNotNull("the leaf has no KeyDescription extension", first[0].getExtensionValue(OID_KEY_DESCRIPTION))
        first.drop(1).forEachIndexed { i, c ->
            assertNull("certificate ${i + 1} carries the KeyDescription extension", c.getExtensionValue(OID_KEY_DESCRIPTION))
        }

        // Read again, through a new key object: byte for byte what the attestation returned.
        val der = first.map { it.encoded }
        val second = DeviceAttestationKey(alias).chain().map { it.encoded }
        assertEquals("the second read has another length", der.size, second.size)
        der.forEachIndexed { i, c -> assertArrayEquals("certificate $i differs at the second read", c, second[i]) }

        val pem = der.joinToString("") { c ->
            "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(c, Base64.NO_WRAP).chunked(PEM_LINE).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
        }
        val file = File(outputDir(), FILE_NAME)
        file.writeText(pem)
        Log.i(TAG, "$BEGIN_MARKER certificates=${der.size}")
        pem.lineSequence().filter { it.isNotEmpty() }.forEach { Log.i(TAG, it) }
        Log.i(TAG, "$END_MARKER file=${file.absolutePath}")

        key.delete()
        assertTrue(!key.exists())
    }

    /**
     * Where the Gradle test runner collects files from the phone (`additionalTestOutputDir`: they land under
     * `core/keystore/build/outputs/connected_android_test_additional_output/`); without it, the test app's own
     * external files directory.
     */
    private fun outputDir(): File {
        val arg = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = arg?.let { File(it) } ?: context.getExternalFilesDir(null) ?: context.filesDir
        dir.mkdirs()
        return dir
    }

    private companion object {
        const val TAG = "VettIDChainDump"
        const val BEGIN_MARKER = "=====BEGIN VETTID ATTESTATION CHAIN====="
        const val END_MARKER = "=====END VETTID ATTESTATION CHAIN====="
        const val FILE_NAME = "vettid-attestation-chain.pem"
        const val OID_KEY_DESCRIPTION = "1.3.6.1.4.1.11129.2.1.17"
        const val MAX_CHAIN = 10
        const val PEM_LINE = 64
    }
}
