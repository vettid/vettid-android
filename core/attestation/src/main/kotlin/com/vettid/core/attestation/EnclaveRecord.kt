package com.vettid.core.attestation

import com.vettid.core.attestation.manifest.ReleaseStatus
import java.time.Instant

/**
 * The app's checks of an enclave (ANDROID-PLAN 0.1.124 §4 "Attestation in detail"; VAULT-MESSAGING §11.2), in the order
 * the app makes them. Each is one line of the attestation screen.
 */
enum class EnclaveCheck {
    /** The release manifest's format, its signature under a pinned key, its serial not going back (§11.10.1). */
    MANIFEST,

    /** The document's format (a COSE_Sign1, ES384 only) and its certificate chain to the pinned AWS Nitro root. */
    CHAIN,

    /** The document's COSE_Sign1 ES384 signature by the chain's leaf. */
    SIGNATURE,

    /** The descriptor's format, and user_data = SHA-256("vettid/vms/2/etk" ‖ descriptor). */
    KEY,

    /** Less than 26 hours old, at most 5 minutes ahead of the phone's clock. */
    RECENT,

    /** No debug PCRs. */
    NOT_DEBUG,

    /** The descriptor's `release` equals PCR0. */
    OWN_RELEASE,

    /** `not_after` in the future and at most 24 h + 5 min after the document. */
    VALIDITY,

    /** PCR0–2 equal to an entry of the manifest. */
    MATCH,
}

/** One line's state. "Passed" only with a recorded pass; anything not run is [NOT_RUN]. */
enum class CheckResult { PASSED, FAILED, NOT_RUN }

/** Why a verification whose nine checks all passed was still refused (ANDROID-PLAN 0.1.124: "Every check passed, but…"). */
enum class EnclaveRefusal {
    /** The release is not `active`, and this was an enrollment. */
    NOT_ACTIVE,

    /** The release is older than one this phone has unlocked into (§11.10.6). */
    OLDER_RELEASE,
}

/**
 * The record of one verification of the enclave instance the vault runs on: each check's result, the values the attestation
 * screen shows, and when it was made. Public material only: nothing of the account, the phone or a secret.
 *
 * A value is in the record only once what carries it has been read; whether it may be shown plainly is [verified] of the
 * checks it rests on (the screen says "Not verified" otherwise).
 */
data class EnclaveRecord(
    val at: Instant,
    val results: Map<EnclaveCheck, CheckResult>,
    /** The manifest's signing key id and serial: once [EnclaveCheck.MANIFEST] passed. */
    val manifestKeyId: String? = null,
    val manifestSerial: Long? = null,
    val pcr0: String? = null,
    val pcr1: String? = null,
    val pcr2: String? = null,
    /** The attestation document's timestamp. */
    val attestedAt: Instant? = null,
    /** The descriptor's `not_after`. */
    val notAfter: Instant? = null,
    /** The matching manifest entry: once [EnclaveCheck.MATCH] passed. */
    val releaseNumber: Long? = null,
    val releaseStatus: ReleaseStatus? = null,
    val releaseEndsAt: Instant? = null,
    val refusal: EnclaveRefusal? = null,
) {
    fun result(c: EnclaveCheck): CheckResult = results[c] ?: CheckResult.NOT_RUN

    /** Every one of [checks] passed. */
    fun verified(vararg checks: EnclaveCheck): Boolean = checks.all { result(it) == CheckResult.PASSED }

    /** The check that failed, or null. */
    val failed: EnclaveCheck? get() = EnclaveCheck.entries.firstOrNull { result(it) == CheckResult.FAILED }

    /** Every check passed. */
    val allPassed: Boolean get() = EnclaveCheck.entries.all { result(it) == CheckResult.PASSED }

    companion object {
        /**
         * The results when [passed] passed, in order, and the next one failed (null: none failed); every later check is
         * [CheckResult.NOT_RUN]. Built only this way, so "Passed" never follows a check that did not pass.
         */
        fun results(passed: Int, failed: Boolean): Map<EnclaveCheck, CheckResult> = EnclaveCheck.entries.associateWith {
            when {
                it.ordinal < passed -> CheckResult.PASSED
                failed && it.ordinal == passed -> CheckResult.FAILED
                else -> CheckResult.NOT_RUN
            }
        }

        /** A manifest refused (its signature, key, format or serial): line 1 failed, nothing else checked. */
        fun manifestFailed(at: Instant): EnclaveRecord = EnclaveRecord(at, results(0, failed = true))
    }
}

/** Where a verification's record goes: the phone keeps the last one (ANDROID-PLAN §5 "What the phone keeps"). */
fun interface EnclaveRecords {
    fun record(r: EnclaveRecord)
}

/** A verification: the verified enclave, or the failure; and its record either way. */
class EnclaveVerification(private val verified: VerifiedEnclave?, private val failure: AttestationException?, val record: EnclaveRecord) {
    /** The verified enclave, or the failure thrown. */
    fun enclave(): VerifiedEnclave = verified ?: throw (failure ?: AttestationException.Format("verification"))
}
