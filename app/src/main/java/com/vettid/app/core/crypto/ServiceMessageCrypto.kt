package com.vettid.app.core.crypto

import android.util.Base64
import com.google.crypto.tink.subtle.Ed25519Sign
import com.google.crypto.tink.subtle.Ed25519Verify
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import com.google.gson.Gson
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cryptographic operations for service message encryption.
 *
 * Uses XChaCha20-Poly1305 for encryption and Ed25519 for signing.
 * All messages are encrypted end-to-end between the app and service.
 *
 * Issue #31 [AND-042] - Service message encryption implementation.
 */
@Singleton
class ServiceMessageCrypto @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    private val gson = Gson()
    private val secureRandom = SecureRandom()

    companion object {
        private const val SERVICE_MESSAGE_DOMAIN = "vettid-service-message"
        private const val SERVICE_AUTH_DOMAIN = "vettid-service-auth"
        private const val SIGNATURE_CONTEXT = "vettid-service-sig-v1"
    }

    // MARK: - Message Encryption

    /**
     * Encrypt a message payload for a service.
     *
     * Uses X25519 key exchange + HKDF + XChaCha20-Poly1305:
     * 1. Generate ephemeral X25519 keypair
     * 2. Compute shared secret with recipient's public key
     * 3. Derive encryption key using HKDF
     * 4. Encrypt with XChaCha20-Poly1305
     *
     * @param payload Message payload (JSON bytes)
     * @param recipientPublicKey Service's X25519 public key (Base64)
     * @param signingPrivateKey Optional Ed25519 private key for signing
     * @return Encrypted message with metadata
     */
    fun encrypt(
        payload: ByteArray,
        recipientPublicKey: String,
        signingPrivateKey: ByteArray? = null
    ): EncryptedServiceMessage {
        val eventId = UUID.randomUUID().toString()

        // 1. Generate ephemeral X25519 keypair
        val ephemeralKeyPair = cryptoManager.generateX25519KeyPair()
        val ephemeralPrivate = ephemeralKeyPair.first
        val ephemeralPublic = ephemeralKeyPair.second

        // 2. Compute shared secret
        val recipientKey = Base64.decode(recipientPublicKey, Base64.NO_WRAP)
        val sharedSecret = cryptoManager.x25519SharedSecret(ephemeralPrivate, recipientKey)

        // 3. Derive encryption key
        val encryptionKey = deriveKey(sharedSecret, SERVICE_MESSAGE_DOMAIN)

        // 4. Encrypt with XChaCha20-Poly1305
        val (ciphertext, nonce) = cryptoManager.xChaChaEncrypt(payload, encryptionKey)

        // 5. Sign if signing key provided
        val signature = signingPrivateKey?.let { privateKey ->
            signMessage(eventId, ciphertext, privateKey)
        }

        // Clear sensitive data
        ephemeralPrivate.fill(0)
        sharedSecret.fill(0)
        encryptionKey.fill(0)

        return EncryptedServiceMessage(
            eventId = eventId,
            ephemeralPublicKey = Base64.encodeToString(ephemeralPublic, Base64.NO_WRAP),
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
            signature = signature?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
        )
    }

    /**
     * Decrypt a message from a service.
     *
     * @param message Encrypted message
     * @param connectionPrivateKey Our X25519 connection private key
     * @param senderPublicKey Optional Ed25519 public key for verification
     * @return Decrypted payload bytes
     * @throws SecurityException if signature verification fails
     */
    fun decrypt(
        message: EncryptedServiceMessage,
        connectionPrivateKey: ByteArray,
        senderPublicKey: ByteArray? = null
    ): ByteArray {
        // Verify signature if public key provided
        if (senderPublicKey != null && message.signature != null) {
            val ciphertext = Base64.decode(message.ciphertext, Base64.NO_WRAP)
            val signature = Base64.decode(message.signature, Base64.NO_WRAP)

            if (!verifySignature(message.eventId, ciphertext, signature, senderPublicKey)) {
                throw SecurityException("Message signature verification failed")
            }
        }

        // Compute shared secret
        val ephemeralPublic = Base64.decode(message.ephemeralPublicKey, Base64.NO_WRAP)
        val sharedSecret = cryptoManager.x25519SharedSecret(connectionPrivateKey, ephemeralPublic)

        // Derive decryption key
        val decryptionKey = deriveKey(sharedSecret, SERVICE_MESSAGE_DOMAIN)

        // Decrypt
        val ciphertext = Base64.decode(message.ciphertext, Base64.NO_WRAP)
        val nonce = Base64.decode(message.nonce, Base64.NO_WRAP)
        val plaintext = cryptoManager.xChaChaDecrypt(ciphertext, nonce, decryptionKey)

        // Clear sensitive data
        sharedSecret.fill(0)
        decryptionKey.fill(0)

        return plaintext
    }

    // MARK: - Authentication Request Encryption

    /**
     * Encrypt an authentication response for a service.
     *
     * @param approved Whether the auth request was approved
     * @param requestId The auth request ID
     * @param recipientPublicKey Service's X25519 public key
     * @param signingPrivateKey Our Ed25519 signing key
     * @return Encrypted auth response
     */
    fun encryptAuthResponse(
        approved: Boolean,
        requestId: String,
        recipientPublicKey: String,
        signingPrivateKey: ByteArray
    ): EncryptedServiceMessage {
        val response = AuthResponsePayload(
            requestId = requestId,
            approved = approved,
            timestamp = System.currentTimeMillis()
        )
        val payload = gson.toJson(response).toByteArray(Charsets.UTF_8)
        return encrypt(payload, recipientPublicKey, signingPrivateKey)
    }

    /**
     * Encrypt an authorization response for a service.
     *
     * @param approved Whether the authz request was approved
     * @param requestId The authz request ID
     * @param action The action being authorized
     * @param resource Optional resource being accessed
     * @param recipientPublicKey Service's X25519 public key
     * @param signingPrivateKey Our Ed25519 signing key
     * @return Encrypted authz response
     */
    fun encryptAuthzResponse(
        approved: Boolean,
        requestId: String,
        action: String,
        resource: String?,
        recipientPublicKey: String,
        signingPrivateKey: ByteArray
    ): EncryptedServiceMessage {
        val response = AuthzResponsePayload(
            requestId = requestId,
            approved = approved,
            action = action,
            resource = resource,
            timestamp = System.currentTimeMillis()
        )
        val payload = gson.toJson(response).toByteArray(Charsets.UTF_8)
        return encrypt(payload, recipientPublicKey, signingPrivateKey)
    }

    // MARK: - Ed25519 Signing

    /**
     * Sign a message using Ed25519.
     *
     * The signature covers: eventId + ciphertext
     */
    private fun signMessage(
        eventId: String,
        ciphertext: ByteArray,
        privateKey: ByteArray
    ): ByteArray {
        val messageToSign = buildSignaturePayload(eventId, ciphertext)
        val signer = Ed25519Sign(privateKey)
        return signer.sign(messageToSign)
    }

    /**
     * Verify an Ed25519 signature.
     */
    private fun verifySignature(
        eventId: String,
        ciphertext: ByteArray,
        signature: ByteArray,
        publicKey: ByteArray
    ): Boolean {
        return try {
            val messageToVerify = buildSignaturePayload(eventId, ciphertext)
            val verifier = Ed25519Verify(publicKey)
            verifier.verify(signature, messageToVerify)
            true
        } catch (e: Exception) {
            android.util.Log.w("ServiceMessageCrypto", "Signature verification failed", e)
            false
        }
    }

    /**
     * Build the payload for signing/verification.
     * Format: context_length (4 bytes) + context + eventId_bytes + ciphertext
     */
    private fun buildSignaturePayload(eventId: String, ciphertext: ByteArray): ByteArray {
        val contextBytes = SIGNATURE_CONTEXT.toByteArray(Charsets.UTF_8)
        val eventIdBytes = eventId.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(4 + contextBytes.size + eventIdBytes.size + ciphertext.size)
        buffer.putInt(contextBytes.size)
        buffer.put(contextBytes)
        buffer.put(eventIdBytes)
        buffer.put(ciphertext)

        return buffer.array()
    }

    // MARK: - Key Derivation

    /**
     * Derive key using HKDF-SHA256.
     */
    private fun deriveKey(sharedSecret: ByteArray, domain: String): ByteArray {
        return Hkdf.computeHkdf(
            "HMACSHA256",
            sharedSecret,
            domain.toByteArray(Charsets.UTF_8),
            ByteArray(0),
            32
        )
    }

    // MARK: - Key Generation

    /**
     * Generate an Ed25519 signing keypair.
     *
     * @return Pair of (privateKey, publicKey)
     */
    fun generateSigningKeyPair(): Pair<ByteArray, ByteArray> {
        val keyPair = Ed25519Sign.KeyPair.newKeyPair()
        return Pair(keyPair.privateKey, keyPair.publicKey)
    }

    /**
     * Get public key from Ed25519 private key.
     */
    fun getSigningPublicKey(privateKey: ByteArray): ByteArray {
        // Ed25519 private key is 64 bytes: 32 bytes seed + 32 bytes public key
        return if (privateKey.size == 64) {
            privateKey.copyOfRange(32, 64)
        } else {
            // Generate full keypair from seed
            val keyPair = Ed25519Sign.KeyPair.newKeyPairFromSeed(privateKey)
            keyPair.publicKey
        }
    }
}

// MARK: - Data Classes

/**
 * Encrypted service message structure.
 */
data class EncryptedServiceMessage(
    val eventId: String,
    val ephemeralPublicKey: String,
    val ciphertext: String,
    val nonce: String,
    val signature: String? = null
)

/**
 * Authentication response payload.
 */
data class AuthResponsePayload(
    val requestId: String,
    val approved: Boolean,
    val timestamp: Long
)

/**
 * Authorization response payload.
 */
data class AuthzResponsePayload(
    val requestId: String,
    val approved: Boolean,
    val action: String,
    val resource: String?,
    val timestamp: Long
)

/**
 * Decrypted service request (auth/authz).
 */
data class ServiceApprovalRequest(
    val requestId: String,
    val requestType: ApprovalRequestType,
    val serviceId: String,
    val serviceName: String,
    val purpose: String,
    val action: String? = null,
    val resource: String? = null,
    val context: Map<String, Any> = emptyMap(),
    val expiresAt: Long
)

/**
 * Type of approval request.
 */
enum class ApprovalRequestType {
    AUTHENTICATION,
    AUTHORIZATION
}
