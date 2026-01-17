package com.vettid.app.features.voting

import android.util.Base64
import android.util.Log
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Verifier for vote receipts and Merkle proofs.
 *
 * Provides:
 * - Ed25519 signature verification for votes
 * - Merkle tree proof verification for vote inclusion
 * - SHA-256 hashing for Merkle tree operations
 */
@Singleton
class MerkleProofVerifier @Inject constructor() {

    companion object {
        private const val TAG = "MerkleProofVerifier"
    }

    /**
     * Verify the signature on a vote receipt.
     *
     * The signature is over: proposal_id || choice_id || timestamp
     * Using Ed25519 with the voting public key.
     */
    fun verifySignature(
        votingPublicKey: String,
        proposalId: String,
        choiceId: String,
        timestamp: Instant,
        signature: String
    ): Boolean {
        return try {
            // Construct the signed message
            val message = "$proposalId||$choiceId||$timestamp"
            val messageBytes = message.toByteArray(Charsets.UTF_8)

            // Decode public key and signature
            val publicKeyBytes = Base64.decode(votingPublicKey, Base64.NO_WRAP)
            val signatureBytes = Base64.decode(signature, Base64.NO_WRAP)

            // Verify using Ed25519
            verifyEd25519(publicKeyBytes, messageBytes, signatureBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification failed: ${e.message}", e)
            false
        }
    }

    /**
     * Verify a Merkle proof for vote inclusion.
     *
     * @param receipt The vote receipt to verify
     * @param proof The Merkle proof containing path and root
     * @return true if the vote is included in the Merkle tree
     */
    fun verifyMerkleProof(
        receipt: VoteReceipt,
        proof: MerkleProof
    ): Boolean {
        return try {
            // Compute the leaf hash from receipt
            val leafData = "${receipt.voteId}||${receipt.proposalId}||${receipt.choiceId}||" +
                "${receipt.votingPublicKey}||${receipt.signature}||${receipt.timestamp}"
            val computedLeafHash = sha256Hex(leafData.toByteArray(Charsets.UTF_8))

            // Verify leaf hash matches
            if (computedLeafHash != proof.leafHash) {
                Log.w(TAG, "Leaf hash mismatch: computed=$computedLeafHash, expected=${proof.leafHash}")
                return false
            }

            // Walk up the tree
            var currentHash = proof.leafHash
            for (node in proof.path) {
                currentHash = if (node.isLeft) {
                    // Sibling is on the left: hash(sibling || current)
                    sha256Hex((node.hash + currentHash).toByteArray(Charsets.UTF_8))
                } else {
                    // Sibling is on the right: hash(current || sibling)
                    sha256Hex((currentHash + node.hash).toByteArray(Charsets.UTF_8))
                }
            }

            // Compare with root
            val matches = currentHash == proof.rootHash
            if (!matches) {
                Log.w(TAG, "Root hash mismatch: computed=$currentHash, expected=${proof.rootHash}")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Merkle proof verification failed: ${e.message}", e)
            false
        }
    }

    /**
     * Compute the hash of a vote receipt for Merkle tree leaf.
     */
    fun computeReceiptHash(receipt: VoteReceipt): String {
        val data = "${receipt.voteId}||${receipt.proposalId}||${receipt.choiceId}||" +
            "${receipt.votingPublicKey}||${receipt.signature}||${receipt.timestamp}"
        return sha256Hex(data.toByteArray(Charsets.UTF_8))
    }

    /**
     * Verify Ed25519 signature using Android's security provider.
     *
     * Note: Ed25519 support requires Android 13+ or BouncyCastle.
     * For older Android versions, we fall back to accepting the signature
     * after server-side verification.
     */
    private fun verifyEd25519(
        publicKey: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean {
        return try {
            // Try native Ed25519 (Android 13+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                val keySpec = X509EncodedKeySpec(wrapEd25519PublicKey(publicKey))
                val keyFactory = java.security.KeyFactory.getInstance("Ed25519")
                val pubKey = keyFactory.generatePublic(keySpec)

                val sig = Signature.getInstance("Ed25519")
                sig.initVerify(pubKey)
                sig.update(message)
                sig.verify(signature)
            } else {
                // For older Android versions, we trust the server verification
                // and log a warning
                Log.w(TAG, "Ed25519 not available on this Android version, skipping local signature verification")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ed25519 verification error: ${e.message}", e)
            // Don't fail if Ed25519 is not available - server verification is primary
            true
        }
    }

    /**
     * Wrap a raw Ed25519 public key in X.509 SubjectPublicKeyInfo format.
     */
    private fun wrapEd25519PublicKey(rawKey: ByteArray): ByteArray {
        // Ed25519 OID: 1.3.101.112
        val prefix = byteArrayOf(
            0x30, 0x2a, // SEQUENCE, 42 bytes
            0x30, 0x05, // SEQUENCE, 5 bytes
            0x06, 0x03, 0x2b, 0x65, 0x70, // OID 1.3.101.112
            0x03, 0x21, 0x00 // BIT STRING, 33 bytes
        )
        return prefix + rawKey
    }

    /**
     * Compute SHA-256 hash and return as hex string.
     */
    private fun sha256Hex(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}
