package com.vettid.core.attestation

/**
 * Every attestation and manifest failure. Messages name the kind of failure
 * and never carry input bytes; [Kind] lets callers (and the UI) tell them
 * apart without parsing messages.
 */
sealed class AttestationException(
    val kind: Kind,
    message: String,
    /** The failure is the release manifest's (its document, signature, key, serial or hash), not an enclave's. */
    val manifest: Boolean = false,
) : Exception(message) {
    enum class Kind { FORMAT, SIGNATURE, CHAIN, PCR, DEBUG, STALE, USER_DATA, NONCE, MANIFEST_KEY, MANIFEST_SERIAL, RELEASE, HASH }

    class Format(what: String) :
        AttestationException(Kind.FORMAT, "malformed: $what", manifest = what.startsWith("manifest") || what.startsWith("served manifest"))

    class Signature(what: String) : AttestationException(Kind.SIGNATURE, "signature invalid: $what", manifest = what == "manifest")

    class Chain : AttestationException(Kind.CHAIN, "certificate chain invalid")

    class Pcr(what: String) : AttestationException(Kind.PCR, "PCR check failed: $what")

    class Debug : AttestationException(Kind.DEBUG, "debug (all-zero) PCRs")

    class Stale : AttestationException(Kind.STALE, "attestation too old or from the future")

    class UserData : AttestationException(Kind.USER_DATA, "user_data mismatch")

    class Nonce : AttestationException(Kind.NONCE, "nonce mismatch")

    class ManifestKey : AttestationException(Kind.MANIFEST_KEY, "manifest: unknown key id", manifest = true)

    class ManifestSerial : AttestationException(Kind.MANIFEST_SERIAL, "manifest: serial lower than one already seen", manifest = true)

    /**
     * A release rule refused (not listed, wrong status, older than the last unlocked one). [unknown]: the enclave's
     * own document verified, but its PCR0 is in no entry of the manifest the app trusts ("not listed").
     */
    class Release(what: String, val unknown: Boolean = false) : AttestationException(Kind.RELEASE, "release: $what")

    /** The manifest is not the one a request names (hash or serial). */
    class Hash : AttestationException(Kind.HASH, "manifest does not match the request", manifest = true)
}
