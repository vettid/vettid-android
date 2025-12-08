package com.vettid.app.core.crypto

import com.vettid.app.core.network.EncryptedCredentialBackup
import com.vettid.app.core.network.RecoveryPhraseException
import com.vettid.app.util.Bip39WordList
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages BIP-39 recovery phrase generation, validation, and key derivation.
 */
@Singleton
class RecoveryPhraseManager @Inject constructor(
    private val cryptoManager: CryptoManager
) {
    companion object {
        private const val ENTROPY_BITS = 256  // 24 words
        private const val ENTROPY_BYTES = ENTROPY_BITS / 8
        private const val WORD_COUNT = 24
        private const val CHECKSUM_BITS = ENTROPY_BITS / 32  // 8 bits for 256-bit entropy
        private const val SALT_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val KEY_DERIVATION_CONTEXT = "credential-backup"
    }

    /**
     * Generate a new 24-word BIP-39 recovery phrase.
     */
    fun generateRecoveryPhrase(): List<String> {
        // Generate 256 bits of entropy
        val entropy = ByteArray(ENTROPY_BYTES)
        SecureRandom().nextBytes(entropy)

        // Calculate checksum (SHA-256 of entropy, take first 8 bits)
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val checksumByte = hash[0]

        // Convert entropy + checksum to 11-bit indices
        val bits = entropy.toBitString() + checksumByte.toBitString().take(CHECKSUM_BITS)

        val words = mutableListOf<String>()
        for (i in 0 until WORD_COUNT) {
            val startBit = i * 11
            val index = bits.substring(startBit, startBit + 11).toInt(2)
            words.add(Bip39WordList.words[index])
        }

        return words
    }

    /**
     * Validate a recovery phrase against the BIP-39 word list and checksum.
     */
    fun validatePhrase(phrase: List<String>): Boolean {
        if (phrase.size != WORD_COUNT) {
            return false
        }

        // Check all words are valid
        val indices = phrase.map { word ->
            val index = Bip39WordList.words.indexOf(word.lowercase())
            if (index == -1) return false
            index
        }

        // Convert indices back to bits
        val bits = indices.joinToString("") {
            it.toString(2).padStart(11, '0')
        }

        // Extract entropy and checksum
        val entropyBits = bits.take(ENTROPY_BITS)
        val checksumBits = bits.takeLast(CHECKSUM_BITS)

        // Convert entropy bits back to bytes
        val entropy = ByteArray(ENTROPY_BYTES)
        for (i in 0 until ENTROPY_BYTES) {
            val byteBits = entropyBits.substring(i * 8, (i + 1) * 8)
            entropy[i] = byteBits.toInt(2).toByte()
        }

        // Verify checksum
        val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
        val expectedChecksum = hash[0].toBitString().take(CHECKSUM_BITS)

        return checksumBits == expectedChecksum
    }

    /**
     * Check if a single word is valid in the BIP-39 word list.
     */
    fun isValidWord(word: String): Boolean {
        return Bip39WordList.isValidWord(word)
    }

    /**
     * Get word suggestions for autocomplete.
     */
    fun getSuggestions(prefix: String): List<String> {
        return Bip39WordList.getSuggestions(prefix)
    }

    /**
     * Derive an encryption key from the recovery phrase using Argon2id.
     */
    fun deriveKeyFromPhrase(phrase: List<String>, salt: ByteArray): ByteArray {
        if (!validatePhrase(phrase)) {
            throw RecoveryPhraseException("Invalid recovery phrase")
        }

        // Combine words into passphrase
        val passphrase = phrase.joinToString(" ")

        // Use Argon2id to derive key
        return cryptoManager.hashPassword(passphrase, salt)
    }

    /**
     * Encrypt credential data for backup using the recovery phrase.
     */
    fun encryptCredentialBackup(
        credentialBlob: ByteArray,
        phrase: List<String>
    ): EncryptedCredentialBackup {
        // Generate salt and nonce
        val salt = ByteArray(SALT_BYTES)
        val nonce = ByteArray(NONCE_BYTES)
        SecureRandom().nextBytes(salt)
        SecureRandom().nextBytes(nonce)

        // Derive key from phrase
        val key = deriveKeyFromPhrase(phrase, salt)

        // Encrypt with ChaCha20-Poly1305 (or AES-GCM as fallback)
        val ciphertext = encryptWithKey(credentialBlob, key, nonce)

        return EncryptedCredentialBackup(
            ciphertext = ciphertext,
            salt = salt,
            nonce = nonce
        )
    }

    /**
     * Decrypt credential backup using the recovery phrase.
     */
    fun decryptCredentialBackup(
        encryptedBackup: EncryptedCredentialBackup,
        phrase: List<String>
    ): ByteArray {
        // Derive key from phrase
        val key = deriveKeyFromPhrase(phrase, encryptedBackup.salt)

        // Decrypt
        return decryptWithKey(encryptedBackup.ciphertext, key, encryptedBackup.nonce)
    }

    /**
     * Encrypt data using AES-GCM.
     */
    private fun encryptWithKey(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt data using AES-GCM.
     */
    private fun decryptWithKey(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        return cipher.doFinal(ciphertext)
    }

    /**
     * Convert a byte to its binary string representation.
     */
    private fun Byte.toBitString(): String {
        return Integer.toBinaryString(this.toInt() and 0xFF).padStart(8, '0')
    }

    /**
     * Convert a byte array to a binary string.
     */
    private fun ByteArray.toBitString(): String {
        return this.joinToString("") { it.toBitString() }
    }
}
