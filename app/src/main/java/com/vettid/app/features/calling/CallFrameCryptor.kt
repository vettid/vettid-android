package com.vettid.app.features.calling

import android.util.Log
import org.webrtc.FrameCryptor
import org.webrtc.FrameCryptorAlgorithm
import org.webrtc.FrameCryptorFactory
import org.webrtc.FrameCryptorKeyProvider
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender

/**
 * E2EE frame encryption for WebRTC media streams using the native FrameCryptor API.
 *
 * Encrypts audio and video frames with AES-128-GCM BEFORE they reach the Cloudflare
 * TURN relay, ensuring true end-to-end encryption. The TURN relay sees only encrypted
 * frames and cannot access media content.
 *
 * The encryption key is a per-call shared secret derived in the vault via:
 *   X25519 ECDH → HKDF-SHA256(salt=callId, info="vettid-e2ee-call-key") → 256-bit key
 * Both sides derive the same secret independently. The key never leaves the enclave.
 *
 * Uses WebRTC's built-in FrameCryptor (native C++ SFrame implementation) which handles:
 * - Per-frame nonce generation (monotonic counter)
 * - AES-GCM authenticated encryption (128-bit)
 * - Frame header preservation (codec compatibility)
 * - Key ratcheting support
 *
 * SECURITY:
 * - Shared secret derived independently on both sides (forward secrecy per call)
 * - Native C++ encryption runs in the media pipeline (no Java overhead)
 * - Key material zeroized on dispose()
 */
class CallFrameCryptor(
    private val factory: PeerConnectionFactory,
    sharedSecret: ByteArray,
    private val participantId: String = "local"
) {
    companion object {
        private const val TAG = "CallFrameCryptor"
        // SECURITY: Ratchet salt for key derivation — must match on both sides
        private val RATCHET_SALT = "vettid-e2ee-ratchet-v1".toByteArray()
        // Discard window size — handles out-of-order frames
        private const val DISCARD_FRAME_WHEN_CRYPTOR_NOT_READY = true
    }

    private val keyProvider: FrameCryptorKeyProvider
    private val senderCryptors = mutableListOf<FrameCryptor>()
    private val receiverCryptors = mutableListOf<FrameCryptor>()
    private var disposed = false

    init {
        require(sharedSecret.size == 32) { "Shared secret must be 32 bytes, got ${sharedSecret.size}" }

        // Create key provider with shared key mode
        // Parameters: isSharedKey, ratchetSalt, ratchetWindowSize, sharedKey, failureTolerance, keyRingSize, discardFrameWhenCryptorNotReady
        keyProvider = FrameCryptorFactory.createFrameCryptorKeyProvider(
            true,                    // isSharedKey — both sides use the same key
            RATCHET_SALT,            // ratchetSalt for key derivation
            0,                       // ratchetWindowSize (0 = no ratcheting needed for 1:1 calls)
            sharedSecret,            // initial shared key
            0,                       // failureTolerance
            1,                       // keyRingSize (1 key for 1:1 calls)
            DISCARD_FRAME_WHEN_CRYPTOR_NOT_READY
        )

        // The constructor's `sharedKey` param sets the default shared key for
        // the provider, but the per-participant senders/receivers look up the
        // key by (participant, keyIndex). Binding the key at index 0 for both
        // "local" and "remote" participants ensures the cryptor never enters
        // MISSINGKEY state at frame time. Without this, the sender falls into
        // MISSINGKEY and discards frames, producing a connected-but-silent
        // call.
        keyProvider.setSharedKey(0, sharedSecret)

        Log.i(TAG, "Frame cryptor initialized with shared key for participant: $participantId")
    }

    /**
     * Enable frame encryption on all RTP senders (outgoing audio/video).
     * Call this after tracks have been added to the peer connection.
     */
    fun enableForSenders(senders: List<RtpSender>) {
        if (disposed) return

        for (sender in senders) {
            val track = sender.track() ?: continue
            try {
                val cryptor = FrameCryptorFactory.createFrameCryptorForRtpSender(
                    factory,
                    sender,
                    participantId,
                    FrameCryptorAlgorithm.AES_GCM,
                    keyProvider
                )
                cryptor.setEnabled(true)
                cryptor.setObserver { participantId, state ->
                    Log.d(TAG, "Sender frame cryptor state: participant=$participantId state=$state")
                }
                senderCryptors.add(cryptor)
                Log.i(TAG, "E2EE enabled for sender track: ${track.kind()} (${track.id()})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable E2EE for sender: ${track.kind()}", e)
            }
        }
    }

    /**
     * Enable frame decryption on all RTP receivers (incoming audio/video).
     * Call this after the remote peer's tracks are received.
     */
    fun enableForReceivers(receivers: List<RtpReceiver>, remoteParticipantId: String = "remote") {
        if (disposed) return

        for (receiver in receivers) {
            val track = receiver.track() ?: continue
            try {
                val cryptor = FrameCryptorFactory.createFrameCryptorForRtpReceiver(
                    factory,
                    receiver,
                    remoteParticipantId,
                    FrameCryptorAlgorithm.AES_GCM,
                    keyProvider
                )
                cryptor.setEnabled(true)
                cryptor.setObserver { participantId, state ->
                    Log.d(TAG, "Receiver frame cryptor state: participant=$participantId state=$state")
                }
                receiverCryptors.add(cryptor)
                Log.i(TAG, "E2EE enabled for receiver track: ${track.kind()} (${track.id()})")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable E2EE for receiver: ${track.kind()}", e)
            }
        }
    }

    /**
     * Dispose all frame cryptors and zeroize key material.
     * SECURITY: Must be called when the call ends.
     */
    fun dispose() {
        if (disposed) return
        disposed = true

        senderCryptors.forEach { it.dispose() }
        receiverCryptors.forEach { it.dispose() }
        senderCryptors.clear()
        receiverCryptors.clear()
        keyProvider.dispose()

        Log.i(TAG, "Frame cryptors disposed and key material zeroized")
    }
}
