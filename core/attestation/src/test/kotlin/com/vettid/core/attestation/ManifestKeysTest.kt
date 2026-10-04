package com.vettid.core.attestation

import com.vettid.core.attestation.manifest.ManifestKeys
import org.junit.Assert.assertEquals
import org.junit.Test

class ManifestKeysTest {
    /** The production keys are exactly key A and key B (VAULT-RELEASES §6.1), as the prod images pin them. */
    @Test
    fun productionKeyIds() {
        assertEquals(listOf("4353463f85c4012f", "1abd49da96970b6e"), ManifestKeys.PRODUCTION.map { it.keyId })
    }
}
