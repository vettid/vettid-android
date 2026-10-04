package com.vettid.core.attestation

/**
 * Every attestation and manifest failure. Messages name the kind of failure
 * and never carry input bytes; [Kind] lets callers (and the UI) tell them
 * apart without parsing messages.
 */
sealed class AttestationException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { FORMAT, SIGNATURE, CHAIN, PCR, DEBUG, STALE, USER_DATA, NONCE, MANIFEST_KEY, MANIFEST_SERIAL, RELEASE, HASH }

    class Format(what: String) : AttestationException(Kind.FORMAT, "malformed: $what")

    class Signature(what: String) : AttestationException(Kind.SIGNATURE, "signature invalid: $what")

    class Chain : AttestationException(Kind.CHAIN, "certificate chain invalid")

    class Pcr(what: String) : AttestationException(Kind.PCR, "PCR check failed: $what")

    class Debug : AttestationException(Kind.DEBUG, "debug (all-zero) PCRs")

    class Stale : AttestationException(Kind.STALE, "attestation too old or from the future")

    class UserData : AttestationException(Kind.USER_DATA, "user_data mismatch")

    class Nonce : AttestationException(Kind.NONCE, "nonce mismatch")

    class ManifestKey : AttestationException(Kind.MANIFEST_KEY, "manifest: unknown key id")

    class ManifestSerial : AttestationException(Kind.MANIFEST_SERIAL, "manifest: serial lower than one already seen")

    /** A release rule refused (not listed, wrong status, older than the last unlocked one). */
    class Release(what: String) : AttestationException(Kind.RELEASE, "release: $what")

    /** The manifest is not the one a request names (hash or serial). */
    class Hash : AttestationException(Kind.HASH, "manifest does not match the request")
}
