package com.vettid.feature.settings

import com.vettid.core.attestation.CheckResult
import com.vettid.core.attestation.EnclaveCheck
import com.vettid.core.attestation.EnclaveCheck.CHAIN
import com.vettid.core.attestation.EnclaveCheck.KEY
import com.vettid.core.attestation.EnclaveCheck.MATCH
import com.vettid.core.attestation.EnclaveCheck.SIGNATURE
import com.vettid.core.attestation.EnclaveRecord
import com.vettid.core.attestation.EnclaveRefusal
import com.vettid.core.attestation.manifest.ReleaseStatus
import com.vettid.core.attestation.nitro.NitroRoot
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * What the attestation screen shows of a record (ANDROID-PLAN 0.1.124 §4 "Attestation in detail"), decided here, without
 * Compose, so that the rules are tested directly: which values are shown, which are "Not verified", whether the Release
 * section exists, and the text of "Copy all".
 */
object AttestationDetail {
    /** The repository the signed release tags are in (VAULT-RELEASES §5.2). Built into the app. */
    const val REPOSITORY = "https://github.com/vettid/vettid-vault"

    /** The root fingerprint shown under line 2: the pinned root's, never one from the network. */
    val rootFingerprint: String get() = NitroRoot.fingerprint

    /** The PCRs and "Attested at" rest on the chain and the hardware signature. */
    fun documentVerified(r: EnclaveRecord): Boolean = r.verified(CHAIN, SIGNATURE)

    /** "Valid until" also rests on "Covers the key this phone encrypts to". */
    fun notAfterVerified(r: EnclaveRecord): Boolean = r.verified(CHAIN, SIGNATURE, KEY)

    /** "Release" (in the Enclave section) and the Release section: only once "Measurements match a signed release" passed. */
    fun releaseShown(r: EnclaveRecord): Boolean = r.verified(MATCH) && r.releaseNumber != null

    /** The manifest's key id and serial under line 1: only once it passed. */
    fun manifestShown(r: EnclaveRecord): Boolean = r.verified(EnclaveCheck.MANIFEST) && r.manifestKeyId != null && r.manifestSerial != null

    /** The release's signed tag, `release/prod/N` or `release/staging/N`; null without a release or a channel. */
    fun tag(r: EnclaveRecord, prefix: String?): String? = if (releaseShown(r) && prefix != null) "$prefix${r.releaseNumber}" else null

    fun tagUrl(tag: String): String = "$REPOSITORY/releases/tag/$tag"

    fun rebuildUrl(tag: String): String = "$REPOSITORY/blob/$tag/docs/RELEASING.md"

    /** The failure notice: none, the check that failed, or a refusal after every check passed. */
    sealed interface Notice {
        data object Failed : Notice

        data class Refused(val why: EnclaveRefusal) : Notice
    }

    fun notice(r: EnclaveRecord): Notice? = when {
        r.failed != null -> Notice.Failed
        r.refusal != null && r.allPassed -> Notice.Refused(r.refusal!!)
        else -> null
    }

    /** The enum's English capitalized name, as the screen shows it. */
    fun statusEnglish(s: ReleaseStatus): String = when (s) {
        ReleaseStatus.ACTIVE -> "Active"
        ReleaseStatus.DEPRECATED -> "Deprecated"
        ReleaseStatus.RETIRED -> "Retired"
        ReleaseStatus.REMOVED -> "Removed"
    }

    private val CHECK_ENGLISH = mapOf(
        EnclaveCheck.MANIFEST to "Release list signed by VettID",
        CHAIN to "Certificate chain to the AWS Nitro root",
        SIGNATURE to "Signed by AWS Nitro hardware",
        KEY to "Covers the key this phone encrypts to",
        EnclaveCheck.RECENT to "Recent",
        EnclaveCheck.NOT_DEBUG to "Not a debug enclave",
        EnclaveCheck.OWN_RELEASE to "Names its own release",
        EnclaveCheck.VALIDITY to "Within its validity",
        MATCH to "Measurements match a signed release",
    )

    private fun resultEnglish(c: CheckResult): String = when (c) {
        CheckResult.PASSED -> "Passed"
        CheckResult.FAILED -> "Failed"
        CheckResult.NOT_RUN -> "Not checked"
    }

    private fun iso(t: Instant): String = DateTimeFormatter.ISO_INSTANT.format(t)

    /**
     * "Copy all": the Enclave, Checks and Release sections as plain text, as shown, in English, values in full, times in
     * ISO 8601 UTC. **This phone and the check's time are left out** (they identify the phone): nothing of the account or
     * the phone, and nothing secret.
     */
    @Suppress("CyclomaticComplexMethod") // one line per row
    fun copyAll(r: EnclaveRecord, environment: String, tagPrefix: String?): String = buildString {
        fun line(s: String) = append(s).append('\n')
        val notVerified = " (Not verified)"
        line("Enclave")
        line("Environment: $environment")
        if (releaseShown(r)) {
            val ends = r.releaseEndsAt?.let { ", ends ${iso(it)}" }.orEmpty()
            line("Release: Release ${r.releaseNumber} · ${r.releaseStatus?.let(::statusEnglish).orEmpty()}$ends")
        }
        val doc = if (documentVerified(r)) "" else notVerified
        r.pcr0?.let { line("PCR0: $it$doc") }
        r.pcr1?.let { line("PCR1: $it$doc") }
        r.pcr2?.let { line("PCR2: $it$doc") }
        r.attestedAt?.let { line("Attested at: ${iso(it)}$doc") }
        r.notAfter?.let { line("Valid until: ${iso(it)}${if (notAfterVerified(r)) "" else notVerified}") }
        line("")
        line("Checks")
        for (c in EnclaveCheck.entries) {
            line("${CHECK_ENGLISH.getValue(c)}: ${resultEnglish(r.result(c))}")
            if (c == EnclaveCheck.MANIFEST && manifestShown(r)) line("  Key ${r.manifestKeyId} · serial ${r.manifestSerial}")
            if (c == CHAIN) line("  Root fingerprint (SHA-256): $rootFingerprint")
        }
        tag(r, tagPrefix)?.let { t ->
            line("")
            line("Release")
            line("Source: $t ${tagUrl(t)}")
            line("Reproducible build: Anyone can rebuild this release from its tag and compare the measurements.")
            line("How to rebuild it: ${rebuildUrl(t)}")
        }
    }.trimEnd('\n')
}
