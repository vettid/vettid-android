package com.vettid.core.altchan

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vettid.core.attestation.android.KeyDescription
import com.vettid.core.attestation.android.RootOfTrust
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.envelope.Timestamps
import com.vettid.core.crypto.envelope.Ulid
import com.vettid.core.keystore.AndroidKeys
import com.vettid.core.keystore.DeviceAttestationKey
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant

/**
 * The production attester on a real phone (VAULT-MESSAGING §11.7): the
 * Keystore key's chain ends at one of Google's pinned attestation roots,
 * the key description meets the enclave's requirements, and on GrapheneOS
 * the verified boot key is one of the pinned GrapheneOS keys. This is the
 * evidence for the dev-stack decision (devstack/README.md): a real phone
 * would pass the production policy (given the release app's package and
 * signing key), but not the dev enclave's TEST-only policy.
 */
@RunWith(AndroidJUnit4::class)
class KeystoreAttesterDeviceTest {
    private val alias = "vettid.test.altchan.attester"

    @After
    fun cleanup() = AndroidKeys.delete(alias)

    private fun googleRoots(): List<X509Certificate> {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        return ctx.assets.open("google-attestation-roots.txt").use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            .map { it as X509Certificate }
    }

    @Test
    fun enrollmentAttestationMeetsTheProductionPolicy() {
        val att = KeystoreAttester(DeviceAttestationKey(alias))
        val rid = Ulid.new()
        val challenge = AltChannel.devattChallenge(rid, "", Timestamps.formatMillis(Instant.now()))
        val da = att.attest(challenge)
        val chain = da.chain().map { CertificateFactory.getInstance("X.509").generateCertificate(it.inputStream()) as X509Certificate }
        // Each certificate is signed by the next; the last is a Google root, compared by public key (§11.7).
        for (i in 0 until chain.size - 1) chain[i].verify(chain[i + 1].publicKey)
        val root = chain.last()
        val pinned = googleRoots().firstOrNull { it.publicKey.encoded.contentEquals(root.publicKey.encoded) }
        Log.i(TAG, "chain: ${chain.size} certificates, root ${pinned?.subjectX500Principal}")
        assertTrue("chain does not end at a pinned Google root", pinned != null)

        val kd = KeyDescription.parse(chain[0])
        assertEquals(emptyList<String>(), kd.problems(challenge))
        assertArrayEquals(challenge, kd.challenge())
        val rot = kd.rootOfTrust!!
        val bootKey = Bytes.hex(rot.verifiedBootKey())
        Log.i(TAG, "level=${kd.keyMintSecurityLevel} boot=${rot.verifiedBootState} locked=${rot.deviceLocked} bootKey=$bootKey")
        if (rot.verifiedBootState == RootOfTrust.SELF_SIGNED) {
            assertTrue("SelfSigned boot key $bootKey is not a pinned GrapheneOS key", bootKey in GRAPHENEOS_BOOT_KEYS)
        }

        // An unlock assertion over a challenge with the vault id, and a release approval string.
        val unlockChallenge = AltChannel.devattChallenge(Ulid.new(), "0123456789abcdef0123456789abcdef", Timestamps.formatMillis(Instant.now()))
        for (msg in listOf(unlockChallenge, "vettid/release-approval/1\nv\nr".toByteArray())) {
            val sig = att.assert(msg).sig()
            val ok = Signature.getInstance("SHA256withECDSA").run {
                initVerify(chain[0].publicKey)
                update(msg)
                verify(sig)
            }
            assertTrue(ok)
        }
    }

    private companion object {
        const val TAG = "KeystoreAttesterTest"

        /** vettid-vault vms/pins/grapheneos.go (VAULT-MESSAGING §11.7), copied 2026-10-04. */
        val GRAPHENEOS_BOOT_KEYS = setOf(
            "d8f879d10419eddc9fcda6280718be763f6bf12299e1f72df3ea8ad8a8eb7f80", // Pixel 10a
            "55a2d44103e56d5ec65496399c417987ba77730e6488fc60ba058d09fc3caee3", // Pixel 10 Pro Fold
            "141d7fc32af7958a416f2661b37cf6f27bfb376fb5ce616aeaa27a82c7a04f74", // Pixel 10 Pro XL
            "4e8ee8f717754052198ca6d2d3aaa232e2461b4293c0d6f297e519cc778de093", // Pixel 10 Pro
            "3f7415ea26f5df5b14ea6d153256071a7a1af9ce7b0970b7311cc463c7ea02c7", // Pixel 10
            "0508de44ee00bfb49ece32c418af1896391abde0f05b64f41bc9a2dfb589445b", // Pixel 9a
            "af4d2c6e62be0fec54f0271b9776ff061dd8392d9f51cf6ab1551d346679e24c", // Pixel 9 Pro Fold
            "55d3c2323db91bb91f20d38d015e85112d038f6b6b5738fe352c1a80dba57023", // Pixel 9 Pro XL
            "f729cab861da1b83fdfab402fc9480758f2ae78ee0b61c1f2137dd1ab7076e86", // Pixel 9 Pro
            "9e6a8f3e0d761a780179f93acd5721ba1ab7c8c537c7761073c0a754b0e932de", // Pixel 9
            "096b8bd6d44527a24ac1564b308839f67e78202185cbff9cfdcb10e63250bc5e", // Pixel 8a
            "896db2d09d84e1d6bb747002b8a114950b946e5825772a9d48ba7eb01d118c1c", // Pixel 8 Pro
            "cd7479653aa88208f9f03034810ef9b7b0af8a9d41e2000e458ac403a2acb233", // Pixel 8
        )
    }
}
