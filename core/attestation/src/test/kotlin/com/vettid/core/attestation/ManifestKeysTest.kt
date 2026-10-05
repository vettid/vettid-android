package com.vettid.core.attestation

import com.vettid.core.attestation.manifest.ManifestKeys
import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestKeysTest {
    /** The production keys are exactly key A and key B (VAULT-RELEASES §6.1), as the prod images pin them. */
    @Test
    fun productionKeyIds() {
        assertEquals(listOf("4353463f85c4012f", "1abd49da96970b6e"), ManifestKeys.PRODUCTION.map { it.keyId })
    }

    /** The staging pin is exactly the staging channel's key A (vettid-vault releasecfg/staging.json). */
    @Test
    fun stagingKeyId() {
        assertEquals(listOf("e9b3a403423120ac"), ManifestKeys.STAGING.map { it.keyId })
    }

    /** Neither environment pins a key of the other. */
    @Test
    fun pinsAreDisjoint() {
        val prod = ManifestKeys.PRODUCTION.map { it.keyId }.toSet()
        assertTrue(ManifestKeys.STAGING.none { it.keyId in prod })
    }

    /**
     * The manifest staging.vettid.org served (serial 1, release S1 active),
     * copied from vettid.org vault/staging/pcr-manifest.json: it verifies
     * under the staging pin and fails closed under the production pins.
     */
    @Test
    fun servedStagingManifestVerifiesUnderStagingPinOnly() {
        val doc = javaClass.getResourceAsStream("/manifest/staging-pcr-manifest-serial1.json")!!.use { it.readBytes() }
        val m = ManifestVerifier(ManifestKeys.STAGING).verify(doc)
        assertEquals(1L, m.serial)
        val r1 = m.byNumber(1)!!
        assertEquals(ReleaseStatus.ACTIVE, r1.status)
        assertEquals(r1, m.byPcr0("cbaf7412603d1b54dad85c2a7769396c153d20c6f4a1c0f5a13341653352edee48ecbdc31615a058f50e2685b61bf8e5"))
        assertThrows(AttestationException.ManifestKey::class.java) { ManifestVerifier(ManifestKeys.PRODUCTION).verify(doc) }
    }
}
