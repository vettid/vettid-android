package com.vettid.app.core.attestation

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verify a Nitro attestation document, force-refreshing the cached PCR
 * manifest and retrying once if the first attempt fails on a PCR
 * mismatch. This is the fix for #234: an enclave that has been
 * redeployed past the user's cached manifest is otherwise unreachable
 * until the next AppLifecycleObserver TTL tick happens to refresh the
 * cache, which can keep a user locked out for a long time.
 *
 * Centralizing this here prevents the two attestation call sites
 * (BootstrapClient cold-unlock + NitroEnrollmentClient enrollment)
 * from drifting in their retry semantics.
 */
@Singleton
class AttestationRetryingVerifier @Inject constructor(
    private val verifier: NitroAttestationVerifier,
    private val pcrConfigManager: PcrConfigManager,
    private val pcrInitializationService: PcrInitializationService,
) {
    companion object {
        private const val TAG = "AttestationRetry"
    }

    /**
     * Verify with one automatic refresh-and-retry on PCR mismatch.
     * Throws AttestationVerificationException on terminal failure so
     * the caller can distinguish a PCR-cache problem (now retried) from
     * a non-PCR verification failure (sig invalid, nonce mismatch, etc.).
     * Pass [callerTag] for log attribution.
     */
    suspend fun verifyWithRefreshOnMismatch(
        attestationDocBase64: String,
        expectedNonce: ByteArray?,
        callerTag: String,
    ): VerifiedAttestation {
        val cached = pcrConfigManager.getCurrentPcrs()
        try {
            return verifier.verify(
                attestationDocBase64 = attestationDocBase64,
                expectedPcrs = cached,
                expectedNonce = expectedNonce,
            )
        } catch (e: AttestationVerificationException) {
            // Only retry when the failure looks like a PCR-cache problem.
            // Signature or nonce failures won't be cured by a fresh
            // manifest and we don't want to mask them with a useless
            // second round-trip.
            if (e.message?.contains("PCR", ignoreCase = true) != true) {
                throw e
            }
            Log.w(TAG, "[$callerTag] PCR mismatch on cached manifest v${cached.version}; force-refreshing and retrying once")
            val refreshResult = pcrInitializationService.forceRefresh()
            if (refreshResult.isFailure) {
                Log.e(TAG, "[$callerTag] PCR manifest force-refresh failed; rethrowing original mismatch", refreshResult.exceptionOrNull())
                throw e
            }
            val fresh = pcrConfigManager.getCurrentPcrs()
            // If the refresh didn't actually advance our manifest, the
            // mismatch isn't going to clear by retrying — give up and
            // surface the original error rather than spinning.
            if (fresh.version == cached.version) {
                Log.w(TAG, "[$callerTag] PCR manifest unchanged after refresh (v${fresh.version}); not retrying")
                throw e
            }
            Log.i(TAG, "[$callerTag] Retrying attestation against fresh manifest v${fresh.version}")
            return verifier.verify(
                attestationDocBase64 = attestationDocBase64,
                expectedPcrs = fresh,
                expectedNonce = expectedNonce,
            )
        }
    }
}
