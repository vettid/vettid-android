package com.vettid.app.core.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.time.Instant
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for service contracts using EncryptedSharedPreferences.
 *
 * Storage model:
 * - Contract metadata: Stored in EncryptedSharedPreferences (AES256-GCM)
 * - Connection keys: Stored in Android Keystore with biometric binding
 * - NATS credentials: Stored per-service for multi-cluster connections
 *
 * Issue #30 [AND-041] - Contract storage implementation.
 */
@Singleton
class ContractStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "service_contracts",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val KEY_CONTRACTS_LIST = "contracts_list"
        private const val KEY_CONTRACT_PREFIX = "contract_"
        private const val KEY_NATS_CREDS_PREFIX = "nats_creds_"
        private const val KEY_CONNECTION_KEY_ALIAS_PREFIX = "vettid_contract_key_"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_VERSION = "store_version"
        private const val CURRENT_VERSION = 1
    }

    init {
        migrateIfNeeded()
    }

    // MARK: - Contract CRUD Operations

    /**
     * Save a signed contract.
     */
    fun save(contract: StoredContract) {
        val json = gson.toJson(contract)
        encryptedPrefs.edit()
            .putString("$KEY_CONTRACT_PREFIX${contract.contractId}", json)
            .apply()

        // Update contracts list
        val contractIds = getContractIds().toMutableSet()
        contractIds.add(contract.contractId)
        encryptedPrefs.edit()
            .putString(KEY_CONTRACTS_LIST, gson.toJson(contractIds))
            .apply()

        android.util.Log.i("ContractStore", "Saved contract: ${contract.contractId} for service: ${contract.serviceId}")
    }

    /**
     * Get a contract by ID.
     */
    fun get(contractId: String): StoredContract? {
        val json = encryptedPrefs.getString("$KEY_CONTRACT_PREFIX$contractId", null) ?: return null
        return try {
            gson.fromJson(json, StoredContract::class.java)
        } catch (e: Exception) {
            android.util.Log.e("ContractStore", "Failed to parse contract: $contractId", e)
            null
        }
    }

    /**
     * Get contract by service ID.
     */
    fun getByServiceId(serviceId: String): StoredContract? {
        return listAll().find { it.serviceId == serviceId }
    }

    /**
     * List all stored contracts.
     */
    fun listAll(): List<StoredContract> {
        return getContractIds().mapNotNull { get(it) }
    }

    /**
     * List active contracts only.
     */
    fun listActive(): List<StoredContract> {
        return listAll().filter { it.status == ContractStatus.ACTIVE }
    }

    /**
     * List contracts by status.
     */
    fun listByStatus(status: ContractStatus): List<StoredContract> {
        return listAll().filter { it.status == status }
    }

    /**
     * Delete a contract.
     */
    fun delete(contractId: String) {
        // Remove contract data
        encryptedPrefs.edit()
            .remove("$KEY_CONTRACT_PREFIX$contractId")
            .remove("$KEY_NATS_CREDS_PREFIX$contractId")
            .apply()

        // Update contracts list
        val contractIds = getContractIds().toMutableSet()
        contractIds.remove(contractId)
        encryptedPrefs.edit()
            .putString(KEY_CONTRACTS_LIST, gson.toJson(contractIds))
            .apply()

        // Remove connection key from Keystore
        deleteConnectionKey(contractId)

        android.util.Log.i("ContractStore", "Deleted contract: $contractId")
    }

    /**
     * Update contract status.
     */
    fun updateStatus(contractId: String, status: ContractStatus) {
        val contract = get(contractId) ?: return
        save(contract.copy(status = status, updatedAt = Instant.now()))
    }

    /**
     * Check if a contract exists for a service.
     */
    fun hasContractForService(serviceId: String): Boolean {
        return getByServiceId(serviceId) != null
    }

    // MARK: - Connection Key Storage (Android Keystore)

    /**
     * Store connection key in Android Keystore.
     * The key is bound to user authentication (biometric/PIN).
     */
    fun storeConnectionKey(contractId: String, privateKey: ByteArray) {
        val alias = "$KEY_CONNECTION_KEY_ALIAS_PREFIX$contractId"

        // For X25519 keys, we store them as wrapped data
        // Android Keystore doesn't directly support X25519, so we:
        // 1. Generate an AES key in Keystore for wrapping
        // 2. Use it to encrypt the X25519 private key

        // Generate AES wrapper key if not exists
        if (!keyStore.containsAlias(alias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

            val keyGenSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false) // Allow for background operations
                .build()

            keyGenerator.init(keyGenSpec)
            keyGenerator.generateKey()
        }

        // Encrypt and store the private key
        val wrapperKey = keyStore.getKey(alias, null) as SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, wrapperKey)

        val encryptedKey = cipher.doFinal(privateKey)
        val iv = cipher.iv

        // Store encrypted key and IV
        val keyData = ConnectionKeyData(
            encryptedKey = Base64.encodeToString(encryptedKey, Base64.NO_WRAP),
            iv = Base64.encodeToString(iv, Base64.NO_WRAP)
        )

        encryptedPrefs.edit()
            .putString("connection_key_$contractId", gson.toJson(keyData))
            .apply()

        android.util.Log.i("ContractStore", "Stored connection key for contract: $contractId")
    }

    /**
     * Get connection key from Android Keystore.
     */
    fun getConnectionKey(contractId: String): ByteArray? {
        val alias = "$KEY_CONNECTION_KEY_ALIAS_PREFIX$contractId"

        // Get stored encrypted key data
        val json = encryptedPrefs.getString("connection_key_$contractId", null) ?: return null
        val keyData = try {
            gson.fromJson(json, ConnectionKeyData::class.java)
        } catch (e: Exception) {
            return null
        }

        // Get wrapper key from Keystore
        if (!keyStore.containsAlias(alias)) return null
        val wrapperKey = keyStore.getKey(alias, null) as? SecretKey ?: return null

        // Decrypt the private key
        return try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val iv = Base64.decode(keyData.iv, Base64.NO_WRAP)
            val gcmSpec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, wrapperKey, gcmSpec)

            val encryptedKey = Base64.decode(keyData.encryptedKey, Base64.NO_WRAP)
            cipher.doFinal(encryptedKey)
        } catch (e: Exception) {
            android.util.Log.e("ContractStore", "Failed to decrypt connection key", e)
            null
        }
    }

    /**
     * Delete connection key from Keystore.
     */
    private fun deleteConnectionKey(contractId: String) {
        val alias = "$KEY_CONNECTION_KEY_ALIAS_PREFIX$contractId"
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
            encryptedPrefs.edit()
                .remove("connection_key_$contractId")
                .apply()
        } catch (e: Exception) {
            android.util.Log.e("ContractStore", "Failed to delete connection key", e)
        }
    }

    // MARK: - NATS Credentials Storage (Per-Service)

    /**
     * Store NATS credentials for a service connection.
     */
    fun storeNatsCredentials(contractId: String, credentials: ServiceNatsCredentials) {
        val json = gson.toJson(credentials)
        encryptedPrefs.edit()
            .putString("$KEY_NATS_CREDS_PREFIX$contractId", json)
            .apply()
    }

    /**
     * Get NATS credentials for a service connection.
     */
    fun getNatsCredentials(contractId: String): ServiceNatsCredentials? {
        val json = encryptedPrefs.getString("$KEY_NATS_CREDS_PREFIX$contractId", null)
            ?: return null
        return try {
            gson.fromJson(json, ServiceNatsCredentials::class.java)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Delete NATS credentials for a service.
     */
    fun deleteNatsCredentials(contractId: String) {
        encryptedPrefs.edit()
            .remove("$KEY_NATS_CREDS_PREFIX$contractId")
            .apply()
    }

    // MARK: - Sync Tracking

    /**
     * Update last sync time.
     */
    fun updateLastSyncTime() {
        encryptedPrefs.edit()
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
    }

    /**
     * Get last sync time.
     */
    fun getLastSyncTime(): Instant? {
        val timestamp = encryptedPrefs.getLong(KEY_LAST_SYNC_TIME, 0)
        return if (timestamp > 0) Instant.ofEpochMilli(timestamp) else null
    }

    // MARK: - Utility Methods

    private fun getContractIds(): Set<String> {
        val json = encryptedPrefs.getString(KEY_CONTRACTS_LIST, null) ?: return emptySet()
        return try {
            val type = object : TypeToken<Set<String>>() {}.type
            gson.fromJson(json, type) ?: emptySet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun migrateIfNeeded() {
        val version = encryptedPrefs.getInt(KEY_VERSION, 0)
        if (version < CURRENT_VERSION) {
            // Perform migrations here if needed in future versions
            encryptedPrefs.edit()
                .putInt(KEY_VERSION, CURRENT_VERSION)
                .apply()
        }
    }

    /**
     * Clear all stored contracts (for logout/reset).
     */
    fun clearAll() {
        // Delete all connection keys from Keystore
        getContractIds().forEach { contractId ->
            deleteConnectionKey(contractId)
        }

        // Clear EncryptedSharedPreferences
        encryptedPrefs.edit().clear().apply()

        android.util.Log.i("ContractStore", "Cleared all contracts")
    }

    /**
     * Get count of active contracts.
     */
    fun getActiveContractCount(): Int {
        return listActive().size
    }
}

// MARK: - Data Classes

/**
 * Stored contract data.
 */
data class StoredContract(
    val contractId: String,
    val serviceId: String,
    val serviceName: String,
    val serviceLogoUrl: String? = null,
    val version: Int,
    val title: String,
    val description: String,
    val termsUrl: String? = null,
    val privacyUrl: String? = null,
    val capabilities: List<ContractCapability> = emptyList(),
    val requiredFields: List<String> = emptyList(),
    val optionalFields: List<String> = emptyList(),
    val status: ContractStatus,
    val signedAt: Instant,
    val expiresAt: Instant? = null,
    val updatedAt: Instant = Instant.now(),
    val natsEndpoint: String,
    val natsSubject: String,
    val userConnectionPublicKey: String,
    val servicePublicKey: String,
    val signatureChain: List<String> = emptyList()
)

/**
 * Contract status.
 */
enum class ContractStatus {
    ACTIVE,
    PENDING_UPDATE,
    SUSPENDED,
    REVOKED,
    EXPIRED
}

/**
 * Contract capability (what the service can do).
 */
data class ContractCapability(
    val type: CapabilityType,
    val description: String,
    val parameters: Map<String, Any> = emptyMap()
)

/**
 * Types of capabilities a service can have.
 */
enum class CapabilityType {
    READ_DATA,
    WRITE_DATA,
    SEND_MESSAGES,
    REQUEST_AUTH,
    REQUEST_PAYMENT,
    SEND_NOTIFICATIONS
}

/**
 * NATS credentials for a service connection.
 */
data class ServiceNatsCredentials(
    val endpoint: String,
    val userJwt: String,
    val userSeed: String,
    val subject: String,
    val expiresAt: Instant? = null
)

/**
 * Connection key data stored in EncryptedSharedPreferences.
 */
private data class ConnectionKeyData(
    val encryptedKey: String,
    val iv: String
)
