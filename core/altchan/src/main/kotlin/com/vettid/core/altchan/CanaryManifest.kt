package com.vettid.core.altchan

import com.vettid.core.attestation.manifest.ManifestVerifier
import com.vettid.core.attestation.manifest.ReleaseManifest
import java.io.IOException

/**
 * A canary manifest installed on this device (VAULT-RELEASES §10.1 step 9, W10-READINESS P31/B5): a served
 * document signed with a pinned manifest key but not published yet. VettID signs serial s+1 with the release
 * under test `active`, copies it to the vault data bucket (so that the enclave host can hand it to the enclave
 * by hash, §11.5) and does not serve it; the canary phone gets it out of band.
 *
 * It adds no trust: it is verified exactly like the published manifest (signature under a key this build
 * pins, `key_id` selecting it; the strict format of §11.10.1; the serial rule), and it is used only while its
 * serial is higher than the published one. Once VettID publishes it (the same document, step 10) or anything
 * newer, the published manifest wins and the canary document is forgotten ([retire]).
 */
interface CanaryManifestSource {
    /** The installed served document's exact bytes, or null when none is installed. */
    fun served(): ByteArray?

    /**
     * The canary document [served] (exact bytes, as [served] returned them) is no longer needed (the
     * published manifest reached its serial) or no longer verifies: forget it, unless another document
     * replaced it meanwhile.
     */
    fun retire(served: ByteArray)
}

/** A canary manifest refused at installation (after its signature and format verified). */
class CanaryManifestException(val reason: Reason) : IOException("canary manifest: $reason") {
    enum class Reason {
        /** Its serial is lower than the highest serial this device has already used (§11.10.1). */
        OLDER,

        /** The published manifest already has this serial or a higher one: nothing to test. */
        PUBLISHED,
    }
}

/** The installation checks of a canary manifest. */
object CanaryManifests {
    /**
     * Verifies [served] for installation: its signature under a key [verifier] pins, the strict manifest
     * format, a serial not lower than [seen] (the highest this device has used for its vault, 0 without one)
     * and higher than [published] (the published manifest's serial, null when nothing is published or it
     * could not be read). Throws `AttestationException` for the signature and format,
     * [CanaryManifestException] for the serial rules.
     */
    fun check(verifier: ManifestVerifier, served: ByteArray, seen: Long, published: Long?): ReleaseManifest {
        val m = verifier.verify(served)
        if (m.serial < seen) throw CanaryManifestException(CanaryManifestException.Reason.OLDER)
        if (published != null && published >= m.serial) throw CanaryManifestException(CanaryManifestException.Reason.PUBLISHED)
        return m
    }

    /**
     * Which manifest an enroll, unlock or recovery uses (§11.10.1 "How apps learn the manifest", with the
     * canary of VAULT-RELEASES §10.1): the one with the higher serial; the published one when they are equal
     * (the canary document published as it was). [published] is null only when nothing is served yet (404),
     * which before production release 1 is the canary phone's normal case.
     */
    fun choose(published: ReleaseManifest?, canary: ReleaseManifest?): ReleaseManifest? = when {
        canary == null -> published
        published == null -> canary
        published.serial >= canary.serial -> published
        else -> canary
    }
}
