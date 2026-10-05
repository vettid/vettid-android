package com.vettid.core.crypto

/**
 * Suite 2 of VAULT-MESSAGING §4.1: HPKE (RFC 9180) base mode with KEM
 * MLKEM768X25519 (0x647a), KDF HKDF-SHA256 and AEAD ChaCha20-Poly1305 for
 * sealed mode; XChaCha20-Poly1305 for session mode; HKDF-SHA-256 for
 * derivations; Ed25519 for signatures. Also the labels, sizes and downgrade
 * rules (§13.4).
 */
object Suite {
    /** PQC-MIGRATION's classical suite: MUST NOT be sent or accepted. */
    const val SUITE_1 = 1

    /** The suite this code implements. */
    const val SUITE_2 = 2

    /** Reserved for PQC Phase 2. */
    const val SUITE_3 = 3

    const val KEM_ID = 0x647a
    const val KDF_ID = 0x0001
    const val AEAD_ID = 0x0003

    const val EK_SIZE = 1216
    const val ENC_SIZE = 1120
    const val SEED_SIZE = 32
    const val KID_SIZE = 8
    const val KEY_SIZE = 32
    const val X_NONCE_SIZE = 24
    const val TAG_SIZE = 16
    const val EPOCH_ID_SIZE = 16
    const val HASH_SIZE = 32
    const val ED25519_PUBLIC_SIZE = 32
    const val ED25519_SIGNATURE_SIZE = 64

    /** Largest `suites` offer (§6.2). */
    const val MAX_OFFER = 8

    /** Reports whether this implementation speaks suite [s]. */
    fun supported(s: Int): Boolean = s == SUITE_2

    /**
     * Accepts suite [s] against a record's pinned suite: suite 1 never, suites
     * below the pin are a downgrade, unknown suites unsupported. Pin 0 means
     * nothing negotiated yet.
     */
    fun check(s: Int, pinned: Int) {
        when {
            s == SUITE_1 -> throw CryptoException.Suite("suite 1 is never accepted")
            s < SUITE_2 || s > 255 -> throw CryptoException.Suite("unsupported")
            s < pinned -> throw CryptoException.Suite("downgrade")
            !supported(s) -> throw CryptoException.Suite("unsupported")
        }
    }

    /** Checks an hs.init `suites` offer: 1–8 strictly ascending integers in [2, 255], never suite 1. */
    fun validateOffer(suites: List<Int>) {
        if (suites.isEmpty() || suites.size > MAX_OFFER) throw CryptoException.Suite("bad offer")
        suites.forEachIndexed { i, s ->
            if (s == SUITE_1) throw CryptoException.Suite("suite 1 is never accepted")
            if (s < SUITE_2 || s > 255) throw CryptoException.Suite("bad offer")
            if (i > 0 && s <= suites[i - 1]) throw CryptoException.Suite("bad offer")
        }
    }

    /** Picks the highest offered suite this implementation supports, not below the pin. */
    fun negotiate(offered: List<Int>, pinned: Int): Int {
        validateOffer(offered)
        for (s in offered.asReversed()) {
            if (runCatching { check(s, pinned) }.isSuccess) return s
        }
        throw CryptoException.Suite("no common suite")
    }

    /** Verifies a responder's choice: offered, supported, not below the pin. */
    fun checkChosen(chosen: Int, offered: List<Int>, pinned: Int) {
        check(chosen, pinned)
        if (chosen !in offered) throw CryptoException.Suite("no common suite")
    }
}

/** Protocol labels (§4.1 and the sections that define them). ASCII, no terminator. */
object Labels {
    const val KID = "vettid/vms/2/kid"
    const val SEALED = "vettid/vms/2/sealed"
    const val HS_KS = "vettid/vms/2/hs-ks"
    const val HS_KE = "vettid/vms/2/hs-ke"
    const val TH1 = "vettid/vms/2/th1"
    const val TH = "vettid/vms/2/th"
    const val SESSION = "vettid/vms/2/session"
    const val I2R = "vettid/vms/2/i2r"
    const val R2I = "vettid/vms/2/r2i"
    const val KID_I2R = "vettid/vms/2/kid-i2r"
    const val KID_R2I = "vettid/vms/2/kid-r2i"
    const val RK = "vettid/vms/2/rk"
    const val EPOCH = "vettid/vms/2/epoch"
    const val SIG_RESP = "vettid/vms/2/sig-resp"
    const val SIG_FIN = "vettid/vms/2/sig-fin"
    const val SAS = "vettid/vms/2/sas"
    const val SAS_COMMIT = "vettid/vms/2/sas-commit"
    const val BUNDLE = "vettid/vms/2/bundle"
    const val ETK = "vettid/vms/2/etk"
    const val VAULT = "vettid/vms/2/vault"
    const val DEVATT = "vettid/vms/2/devatt"
    const val UNLOCK = "vettid/vms/2/unlock"
    const val RELEASE_APPROVAL = "vettid/vms/2/release-approval"
    const val ROTATE = "vettid/vms/2/rotate"
    const val CREDENTIAL_ROTATE = "vettid/vms/2/credential-rotate"
    const val BLOB = "vettid/vms/2/blob"
    const val UTK = "vettid/vms/2/utk"
    const val REPLY = "vettid/vms/2/reply"
    const val CRITICAL_ITEM = "vettid/vms/2/critical-item"
}
