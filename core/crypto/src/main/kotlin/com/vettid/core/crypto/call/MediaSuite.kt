package com.vettid.core.crypto.call

private const val HKDF_SHA_256 = "HKDF-SHA-256"
private const val LABEL_MEDIA = "vettid/vms/2/call-media"
private const val LABEL_FRAME = "vettid/vms/2/call-frame"

/** The cipher that encrypts media frames. */
enum class FrameCipher { AES_GCM }

/**
 * The media suites of VettID calls: one closed, versioned registry (docs/calls/CALL-MEDIA.md §2). A suite fixes
 * everything two devices must agree on to encrypt a call's media frames: the frame cipher and its key length, the
 * key derivation and its labels, the frame format, and what the suite expects of the transport underneath. Its
 * [id] is what the devices negotiate, and it is bound into the keys they derive.
 *
 * Adding a suite is one entry here, plus the media engine saying that it can do it (its key-provider parameter):
 * nothing in the negotiation, the derivation or the signalling changes. An id is never reused or redefined.
 */
enum class MediaSuite(
    /** The number on the wire, 1–65535. */
    val id: Int,
    val frameCipher: FrameCipher,
    /** The frame key's length in bytes. */
    val frameKeySize: Int,
    /** The KDF of the direction keys and of the frame key. */
    val kdf: String,
    /** HKDF salt of the direction keys. */
    val mediaLabel: String,
    /** HKDF salt of the frame key (the frame cryptor's "ratchet salt"). */
    val frameLabel: String,
    /**
     * Whether the suite may only be used over a DTLS handshake with a post-quantum key exchange. No suite asks for
     * it today: the frame encryption does not rely on DTLS (CALLING-SERVICE §7).
     */
    val pqDtlsRequired: Boolean,
) {
    /** AES-128-GCM frames in the media stack's frame-cryptor format. The suite of every call today. */
    AES_128_GCM(1, FrameCipher.AES_GCM, 16, HKDF_SHA_256, LABEL_MEDIA, LABEL_FRAME, false),

    /**
     * AES-256-GCM frames in the same format, with a 32-byte frame key. The media stack can do it since
     * `io.github.webrtc-sdk:android` 150.7871.04 (its key provider's key size, webrtc-sdk/webrtc PR #302); the
     * derivation and the format were checked against that library on two phones (`FrameFormatDeviceTest` in
     * :core:calls). **Not offered or chosen in form 1** ([MediaSuite.formOne]): it needs key confirmation (form 2).
     */
    AES_256_GCM(2, FrameCipher.AES_GCM, 32, HKDF_SHA_256, LABEL_MEDIA, LABEL_FRAME, false),
    ;

    companion object {
        /** Every registered suite, the preferred one first. */
        val PREFERENCE: List<MediaSuite> = listOf(AES_256_GCM, AES_128_GCM)

        /** What a device offers when its engine says nothing else: suite 1, what every engine can do and all form 1 allows. */
        val DEFAULT: List<MediaSuite> = listOf(AES_128_GCM)

        /**
         * What [supported] may offer or choose in **form 1** (the SDP attribute of [CallMedia], which nothing signs):
         * suite 1 only, or nothing if [supported] lacks it. VAULT-MESSAGING §10.10 "Call forms", rules 1 and 3: a
         * second suite is offered and chosen only with key confirmation before microphone audio (form 2, C2), which
         * form 1 has none of; so a device whose engine can do suite 2 still offers `1` and answers `1` in form 1, and
         * does not ring for a form 1 offer without suite 1.
         */
        fun formOne(supported: Collection<MediaSuite>): List<MediaSuite> = supported.filter { it == AES_128_GCM }

        /** The suite with this number, or null for a number this build does not know. */
        fun byId(id: Int): MediaSuite? = entries.firstOrNull { it.id == id }

        /** [suites] in the order of [PREFERENCE], each once. */
        fun ordered(suites: Collection<MediaSuite>): List<MediaSuite> = PREFERENCE.filter { it in suites }
    }
}
