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
     * not_after in the future and at most 24 h + 5 min after the
     * attestation's timestamp (the clock allowance of §11.2), and
     * PCR0–2 equal to a manifest entry (an `active` one to enroll).
     */
    fun verifyEnclave(
        descriptor: ByteArray,
        attestation: ByteArray,
        manifest: ReleaseManifest,
        enroll: Boolean,
        now: Instant,
    ): VerifiedEnclave = verify(descriptor, attestation, manifest, enroll, now).enclave()

    /**
     * [verifyEnclave] with its record (ANDROID-PLAN 0.1.124 §4 "Attestation in detail"): the same checks, in the order of
     * [EnclaveCheck] ([manifest] has passed line 1), each recorded as it passes; the first that fails is recorded failed
     * and every later one not run. A release that passed every check and is not `active` for an enrollment is refused
     * after them ([EnclaveRefusal.NOT_ACTIVE]).
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // one step per check, in order
    fun verify(
        descriptor: ByteArray,
        attestation: ByteArray,
        manifest: ReleaseManifest,
        enroll: Boolean,
        now: Instant,
    ): EnclaveVerification {
        var r = EnclaveRecord(now, EnclaveRecord.results(1, failed = false), manifest.keyId, manifest.serial)
        var passed = 1
        fun pass() {
            passed++
            r = r.copy(results = EnclaveRecord.results(passed, failed = false))
        }
        fun fail(e: AttestationException): EnclaveVerification =
            EnclaveVerification(null, e, r.copy(results = EnclaveRecord.results(passed, failed = true)))
        // Lines 2 and 3: the chain (and the document's format) is checked before the signature, which is the last
        // thing NitroVerifier checks and the only one it reports as a Signature failure.
        val doc = try {
            nitro.verify(attestation)
        } catch (e: AttestationException.Signature) {
            pass()
            return fail(e)
        } catch (e: AttestationException) {
            return fail(e)
        }
        val ms = doc.measurements
        r = r.copy(pcr0 = ms.pcr0, pcr1 = ms.pcr1, pcr2 = ms.pcr2, attestedAt = doc.timestamp)
        pass()
        pass()
        // Line 4: the descriptor's format and user_data over it.
        val ds = try {
            Descriptor.parse(descriptor)
        } catch (_: CryptoException) {
            return fail(AttestationException.Format("descriptor"))
        }
        r = r.copy(notAfter = ds.notAfter)
        try {
            doc.checkUserData(AltChannel.etkUserData(descriptor))
        } catch (e: AttestationException) {
            return fail(e)
        }
        pass()
        try {
            doc.checkFresh(now, MAX_ATTESTATION_AGE, ATTESTATION_SKEW)
        } catch (e: AttestationException) {
            return fail(e)
        }
        pass()
        if (ms.isDebug) return fail(AttestationException.Debug())
        pass()
        if (!Bytes.constantTimeEquals(ms.pcr0.toByteArray(), ds.release.toByteArray())) return fail(AttestationException.Pcr("release"))
        pass()
        if (!now.isBefore(ds.notAfter) || ds.notAfter.isAfter(doc.timestamp.plus(MAX_DESCRIPTOR_LIFE))) {
            return fail(AttestationException.Stale())
        }
        pass()
        val e = manifest.byPcr0(ms.pcr0) ?: return fail(AttestationException.Release("not listed", unknown = true))
        if (!e.measurements.matches(ms)) return fail(AttestationException.Pcr("manifest"))
        pass()
        r = r.copy(releaseNumber = e.number, releaseStatus = e.status, releaseEndsAt = e.endsAt)
        if (enroll && e.status != ReleaseStatus.ACTIVE) {
            return EnclaveVerification(null, AttestationException.Release("not active"), r.copy(refusal = EnclaveRefusal.NOT_ACTIVE))
        }
        return EnclaveVerification(VerifiedEnclave(ds, ms, e), null, r)
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
        private val MAX_DESCRIPTOR_LIFE: Duration = Duration.ofHours(24).plusMinutes(5)
    }
}
