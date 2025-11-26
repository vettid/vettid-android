package com.vettid.app.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for Protean Credentials using EncryptedSharedPreferences
 */
@Singleton
class CredentialStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vettid_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_CREDENTIAL_IDS = "credential_ids"
        private const val KEY_CREDENTIAL_PREFIX = "credential_"
    }

    // MARK: - Credential Storage

    /**
     * Store a credential securely
     */
    fun store(credential: StoredCredential) {
        val json = gson.toJson(credential)
        encryptedPrefs.edit()
            .putString("$KEY_CREDENTIAL_PREFIX${credential.credentialId}", json)
            .apply()

        // Update credential IDs list
        val ids = getCredentialIds().toMutableSet()
        ids.add(credential.credentialId)
        encryptedPrefs.edit()
            .putStringSet(KEY_CREDENTIAL_IDS, ids)
            .apply()
    }

    /**
     * Retrieve a credential by ID
     */
    fun retrieve(credentialId: String): StoredCredential? {
        val json = encryptedPrefs.getString("$KEY_CREDENTIAL_PREFIX$credentialId", null)
            ?: return null
        return gson.fromJson(json, StoredCredential::class.java)
    }

    /**
     * Check if any credential is stored
     */
    fun hasStoredCredential(): Boolean {
        return getCredentialIds().isNotEmpty()
    }

    /**
     * Get all stored credential IDs
     */
    fun getCredentialIds(): Set<String> {
        return encryptedPrefs.getStringSet(KEY_CREDENTIAL_IDS, emptySet()) ?: emptySet()
    }

    /**
     * Delete a credential by ID
     */
    fun delete(credentialId: String) {
        encryptedPrefs.edit()
            .remove("$KEY_CREDENTIAL_PREFIX$credentialId")
            .apply()

        // Update credential IDs list
        val ids = getCredentialIds().toMutableSet()
        ids.remove(credentialId)
        encryptedPrefs.edit()
            .putStringSet(KEY_CREDENTIAL_IDS, ids)
            .apply()
    }

    /**
     * Update a credential's LAT token after authentication
     */
    fun updateLat(credentialId: String, newLat: ByteArray) {
        val credential = retrieve(credentialId) ?: return
        val updated = credential.copy(
            latCurrent = newLat,
            lastUsedAt = System.currentTimeMillis()
        )
        store(updated)
    }

    /**
     * Mark a transaction key as used
     */
    fun markTransactionKeyUsed(credentialId: String, keyId: String) {
        val credential = retrieve(credentialId) ?: return
        val updatedKeys = credential.transactionKeys.map { key ->
            if (key.keyId == keyId) key.copy(isUsed = true) else key
        }
        val updated = credential.copy(transactionKeys = updatedKeys)
        store(updated)
    }

    /**
     * Get available (unused) transaction keys
     */
    fun getAvailableTransactionKeys(credentialId: String): List<TransactionKey> {
        val credential = retrieve(credentialId) ?: return emptyList()
        return credential.transactionKeys.filter { !it.isUsed }
    }

    /**
     * Clear all credentials (for logout/reset)
     */
    fun clearAll() {
        val ids = getCredentialIds()
        val editor = encryptedPrefs.edit()
        ids.forEach { id ->
            editor.remove("$KEY_CREDENTIAL_PREFIX$id")
        }
        editor.remove(KEY_CREDENTIAL_IDS)
        editor.apply()
    }
}

// MARK: - Data Classes

data class StoredCredential(
    val credentialId: String,
    val vaultId: String,
    val cekKeyAlias: String,        // Keystore alias for CEK
    val signingKeyAlias: String,    // Keystore alias for signing key
    val latCurrent: ByteArray,      // Current LAT token (32 bytes)
    val transactionKeys: List<TransactionKey>,
    val createdAt: Long,
    val lastUsedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StoredCredential
        return credentialId == other.credentialId &&
                vaultId == other.vaultId &&
                cekKeyAlias == other.cekKeyAlias &&
                signingKeyAlias == other.signingKeyAlias &&
                latCurrent.contentEquals(other.latCurrent) &&
                transactionKeys == other.transactionKeys &&
                createdAt == other.createdAt &&
                lastUsedAt == other.lastUsedAt
    }

    override fun hashCode(): Int {
        var result = credentialId.hashCode()
        result = 31 * result + vaultId.hashCode()
        result = 31 * result + cekKeyAlias.hashCode()
        result = 31 * result + signingKeyAlias.hashCode()
        result = 31 * result + latCurrent.contentHashCode()
        result = 31 * result + transactionKeys.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastUsedAt.hashCode()
        return result
    }
}

data class TransactionKey(
    val keyId: String,
    val keyAlias: String,  // Keystore alias
    val publicKey: ByteArray,
    val isUsed: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TransactionKey
        return keyId == other.keyId &&
                keyAlias == other.keyAlias &&
                publicKey.contentEquals(other.publicKey) &&
                isUsed == other.isUsed
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + keyAlias.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + isUsed.hashCode()
        return result
    }
}
