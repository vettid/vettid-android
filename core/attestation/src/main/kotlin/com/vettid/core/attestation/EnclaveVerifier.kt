package com.vettid.core.attestation

import com.vettid.core.attestation.manifest.ReleaseManifest
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.attestation.manifest.Release
import com.vettid.core.attestation.nitro.Measurements
import com.vettid.core.attestation.nitro.NitroVerifier
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.CryptoException
import com.vettid.core.crypto.altchan.AltChannel
import com.vettid.core.crypto.altchan.Descriptor
import java.time.Duration
import java.time.Instant

/** A verified enclave instance (§11.2): its ETK descriptor, measurements and manifest entry. */
class VerifiedEnclave(val descriptor: Descriptor, val measurements: Measurements, val release: Release)

/**
 * The app's checks of an enclave before it seals anything to it
 * (VAULT-MESSAGING §11.2, §11.3), as vettid-vault's reference client does
 * (`client.VerifyEnclave`, `checkEnrolledAttestation`).
 */
class EnclaveVerifier(private val nitro: NitroVerifier = NitroVerifier()) {
    /**
     * Checks an ETK descriptor and its attestation document: the chain to the
     * pinned Nitro root, user_data = SHA-256("vettid/vms/2/etk" || descriptor),
     * an attestation less than 26 h old, no debug PCRs, release = PCR0,
     * not_after in the future and at most 24 h after the attestation, and
     * PCR0–2 equal to a manifest entry (an `active` one to enroll).
     */
    fun verifyEnclave(
        descriptor: ByteArray,
        attestation: ByteArray,
        manifest: ReleaseManifest,
        enroll: Boolean,
        now: Instant,
    ): VerifiedEnclave {
        val ds = try {
            Descriptor.parse(descriptor)
        } catch (_: CryptoException) {
            throw AttestationException.Format("descriptor")
        }
        val doc = nitro.verify(attestation)
        doc.checkUserData(AltChannel.etkUserData(descriptor))
        doc.checkFresh(now, MAX_ATTESTATION_AGE, ATTESTATION_SKEW)
        val ms = doc.measurements
        if (ms.isDebug) throw AttestationException.Debug()
        if (!Bytes.constantTimeEquals(ms.pcr0.toByteArray(), ds.release.toByteArray())) throw AttestationException.Pcr("release")
        if (!now.isBefore(ds.notAfter) || ds.notAfter.isAfter(doc.timestamp.plus(MAX_DESCRIPTOR_LIFE))) {
            throw AttestationException.Stale()
        }
        val e = manifest.byPcr0(ms.pcr0) ?: throw AttestationException.Release("not listed")
        if (!e.measurements.matches(ms)) throw AttestationException.Pcr("manifest")
        if (enroll && e.status != ReleaseStatus.ACTIVE) throw AttestationException.Release("not active")
        return VerifiedEnclave(ds, ms, e)
    }

    /**
     * Checks vault.enrolled's attestation (§11.3): nonce = the app's enroll
     * nonce, user_data = SHA-256("vettid/vms/2/vault" || vault_bundle), the
     * PCRs of the enclave the request was sealed to, and freshness.
     */
    fun verifyEnrolled(attestation: ByteArray, vaultBundle: ByteArray, nonce: ByteArray, expected: Measurements, now: Instant) {
        val doc = nitro.verify(attestation)
        doc.checkNonce(nonce)
        doc.checkUserData(AltChannel.vaultUserData(vaultBundle))
        if (!doc.measurements.matches(expected)) throw AttestationException.Pcr("enrolled")
        doc.checkFresh(now, MAX_ATTESTATION_AGE, ATTESTATION_SKEW)
    }

    companion object {
        /** §11.2 step 3. */
        val MAX_ATTESTATION_AGE: Duration = Duration.ofHours(26)
        val ATTESTATION_SKEW: Duration = Duration.ofMinutes(5)
        private val MAX_DESCRIPTOR_LIFE: Duration = Duration.ofHours(24).plusSeconds(1)
    }
}
