package com.vettid.app.core.storage

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for minor secrets using EncryptedSharedPreferences.
 *
 * Minor secrets are stored in the enclave datastore and synced via
 * secrets.datastore.* NATS topics. These include:
 * - Passwords
 * - API keys
 * - WiFi credentials
 * - Notes
 * - Certificates
 *
 * Unlike critical secrets, minor secrets can be:
 * - Shared with connections
 * - Accessed after password verification (same as critical)
 * - Synced to/from vault
 */
@Singleton
class MinorSecretsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson = Gson()

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "vettid_minor_secrets",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_SECRETS = "minor_secrets"
        private const val KEY_LAST_SYNCED_AT = "last_synced_at"
        private const val KEY_PENDING_SYNC = "pending_sync"
    }

    // MARK: - Secret CRUD Operations

    /**
     * Get all minor secrets.
     */
    fun getAllSecrets(): List<MinorSecret> {
        val json = encryptedPrefs.getString(KEY_SECRETS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<MinorSecret>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Get a secret by ID.
     */
    fun getSecret(id: String): MinorSecret? {
        return getAllSecrets().find { it.id == id }
    }

    /**
     * Add a new secret.
     *
     * @param name Secret name
     * @param value Secret value (encrypted at rest)
     * @param category Secret category
     * @param type Secret type (PUBLIC_KEY, PRIVATE_KEY, etc.)
     * @param notes Optional notes
     * @param isShareable Whether the secret can be shared
     * @param isInPublicProfile Whether to show in public profile (PUBLIC_KEY only)
     * @param isSystemField Whether this is a system field (cannot be deleted)
     * @return The created secret
     */
    fun addSecret(
        name: String,
        value: String,
        category: SecretCategory,
        type: SecretType = SecretType.TEXT,
        notes: String? = null,
        isShareable: Boolean = true,
        isInPublicProfile: Boolean = false,
        isSystemField: Boolean = false
    ): MinorSecret {
        val secrets = getAllSecrets().toMutableList()

        // Calculate sort order (place at end of category)
        val categorySecrets = secrets.filter { it.category == category }
        val maxSortOrder = categorySecrets.maxOfOrNull { it.sortOrder } ?: -1

        val secret = MinorSecret(
            id = UUID.randomUUID().toString(),
            name = name,
            value = value,
            category = category,
            type = type,
            notes = notes,
            isShareable = isShareable,
            isInPublicProfile = isInPublicProfile,
            isSystemField = isSystemField,
            sortOrder = maxSortOrder + 1,
            syncStatus = SyncStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        secrets.add(secret)
        saveSecrets(secrets)
        markPendingSync()

        return secret
    }

    /**
     * Update an existing secret.
     *
     * @param secret The secret to update (matched by ID)
     */
    fun updateSecret(secret: MinorSecret) {
        val secrets = getAllSecrets().toMutableList()
        val index = secrets.indexOfFirst { it.id == secret.id }
        if (index >= 0) {
            secrets[index] = secret.copy(
                updatedAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING
            )
            saveSecrets(secrets)
            markPendingSync()
        }
    }

    /**
     * Delete a secret by ID.
     */
    fun deleteSecret(id: String) {
        val secrets = getAllSecrets().toMutableList()
        secrets.removeAll { it.id == id }
        saveSecrets(secrets)
        markPendingSync()
    }

    /**
     * Search secrets by name or notes.
     */
    fun searchSecrets(query: String): List<MinorSecret> {
        if (query.isBlank()) return getAllSecrets()
        return getAllSecrets().filter {
            it.name.contains(query, ignoreCase = true) ||
            it.notes?.contains(query, ignoreCase = true) == true
        }
    }

    /**
     * Get secrets by category.
     */
    fun getSecretsByCategory(category: SecretCategory): List<MinorSecret> {
        return getAllSecrets().filter { it.category == category }
    }

    private fun saveSecrets(secrets: List<MinorSecret>) {
        val json = gson.toJson(secrets)
        encryptedPrefs.edit().putString(KEY_SECRETS, json).apply()
    }

    // MARK: - Sync Status

    /**
     * Check if there are pending changes to sync.
     */
    fun hasPendingSync(): Boolean {
        return encryptedPrefs.getBoolean(KEY_PENDING_SYNC, false)
    }

    /**
     * Mark that there are pending changes.
     */
    fun markPendingSync() {
        encryptedPrefs.edit().putBoolean(KEY_PENDING_SYNC, true).apply()
    }

    /**
     * Mark sync as complete.
     */
    fun markSyncComplete() {
        encryptedPrefs.edit().apply {
            putBoolean(KEY_PENDING_SYNC, false)
            putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            apply()
        }

        // Update sync status for all secrets
        val secrets = getAllSecrets().map { it.copy(syncStatus = SyncStatus.SYNCED) }
        saveSecrets(secrets)
    }

    /**
     * Mark a specific secret's sync status.
     */
    fun updateSyncStatus(secretId: String, status: SyncStatus) {
        val secrets = getAllSecrets().toMutableList()
        val index = secrets.indexOfFirst { it.id == secretId }
        if (index >= 0) {
            secrets[index] = secrets[index].copy(syncStatus = status)
            saveSecrets(secrets)
        }
    }

    /**
     * Get last sync timestamp.
     */
    fun getLastSyncedAt(): Long {
        return encryptedPrefs.getLong(KEY_LAST_SYNCED_AT, 0)
    }

    // MARK: - Export/Import for Sync

    /**
     * Export secrets for syncing to vault.
     * Returns list of secrets with PENDING sync status.
     */
    fun exportPendingForSync(): List<Map<String, Any?>> {
        return getAllSecrets()
            .filter { it.syncStatus == SyncStatus.PENDING }
            .map { secret ->
                mapOf(
                    "id" to secret.id,
                    "name" to secret.name,
                    "value" to secret.value,
                    "category" to secret.category.name,
                    "type" to secret.type.name,
                    "notes" to secret.notes,
                    "isShareable" to secret.isShareable,
                    "isInPublicProfile" to secret.isInPublicProfile,
                    "isSystemField" to secret.isSystemField,
                    "sortOrder" to secret.sortOrder,
                    "createdAt" to secret.createdAt,
                    "updatedAt" to secret.updatedAt
                )
            }
    }

    /**
     * Import secrets from vault sync response.
     */
    fun importFromSync(secretsData: List<Map<String, Any?>>) {
        val importedSecrets = secretsData.mapNotNull { data ->
            try {
                MinorSecret(
                    id = data["id"] as String,
                    name = data["name"] as String,
                    value = data["value"] as String,
                    category = try {
                        SecretCategory.valueOf(data["category"] as String)
                    } catch (e: Exception) {
                        SecretCategory.OTHER
                    },
                    type = try {
                        SecretType.valueOf(data["type"] as? String ?: "TEXT")
                    } catch (e: Exception) {
                        SecretType.TEXT
                    },
                    notes = data["notes"] as? String,
                    isShareable = data["isShareable"] as? Boolean ?: true,
                    isInPublicProfile = data["isInPublicProfile"] as? Boolean ?: false,
                    isSystemField = data["isSystemField"] as? Boolean ?: false,
                    sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0,
                    syncStatus = SyncStatus.SYNCED,
                    createdAt = (data["createdAt"] as Number).toLong(),
                    updatedAt = (data["updatedAt"] as Number).toLong()
                )
            } catch (e: Exception) {
                null
            }
        }

        // Merge with existing secrets (imported ones take precedence)
        val existingSecrets = getAllSecrets()
        val mergedSecrets = existingSecrets
            .filter { existing -> importedSecrets.none { it.id == existing.id } }
            .plus(importedSecrets)

        saveSecrets(mergedSecrets)
        encryptedPrefs.edit().apply {
            putBoolean(KEY_PENDING_SYNC, false)
            putLong(KEY_LAST_SYNCED_AT, System.currentTimeMillis())
            apply()
        }
    }

    // MARK: - Public Profile

    /**
     * Get secrets that are in the public profile.
     * Only PUBLIC_KEY types can be in the public profile.
     */
    fun getPublicProfileSecrets(): List<MinorSecret> {
        return getAllSecrets().filter { it.isInPublicProfile && it.type == SecretType.PUBLIC_KEY }
    }

    /**
     * Toggle whether a secret is in the public profile.
     * Only PUBLIC_KEY types can be toggled.
     */
    fun togglePublicProfile(secretId: String): Boolean {
        val secrets = getAllSecrets().toMutableList()
        val index = secrets.indexOfFirst { it.id == secretId }
        if (index >= 0) {
            val secret = secrets[index]
            // Only PUBLIC_KEY types can be in public profile
            if (secret.type != SecretType.PUBLIC_KEY) return false

            secrets[index] = secret.copy(
                isInPublicProfile = !secret.isInPublicProfile,
                updatedAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.PENDING
            )
            saveSecrets(secrets)
            markPendingSync()
            return true
        }
        return false
    }

    // MARK: - Sort Order

    /**
     * Update the sort order of a secret within its category.
     */
    fun updateSortOrder(secretId: String, newSortOrder: Int) {
        val secrets = getAllSecrets().toMutableList()
        val index = secrets.indexOfFirst { it.id == secretId }
        if (index >= 0) {
            secrets[index] = secrets[index].copy(
                sortOrder = newSortOrder,
                updatedAt = System.currentTimeMillis()
            )
            saveSecrets(secrets)
        }
    }

    /**
     * Move a secret up in its category (decrease sort order).
     * Swaps sort order with the item above it.
     */
    fun moveSecretUp(secretId: String): Boolean {
        val secrets = getAllSecrets().toMutableList()
        val secret = secrets.find { it.id == secretId } ?: return false

        val categorySecrets = secrets.filter { it.category == secret.category }
            .sortedBy { it.sortOrder }

        val index = categorySecrets.indexOfFirst { it.id == secretId }
        if (index <= 0) return false

        val itemAbove = categorySecrets[index - 1]
        val newSortOrder = itemAbove.sortOrder
        val aboveSortOrder = secret.sortOrder

        // Update both items
        val secretIndex = secrets.indexOfFirst { it.id == secretId }
        val aboveIndex = secrets.indexOfFirst { it.id == itemAbove.id }

        secrets[secretIndex] = secrets[secretIndex].copy(sortOrder = newSortOrder)
        secrets[aboveIndex] = secrets[aboveIndex].copy(sortOrder = aboveSortOrder)

        saveSecrets(secrets)
        return true
    }

    /**
     * Move a secret down in its category (increase sort order).
     * Swaps sort order with the item below it.
     */
    fun moveSecretDown(secretId: String): Boolean {
        val secrets = getAllSecrets().toMutableList()
        val secret = secrets.find { it.id == secretId } ?: return false

        val categorySecrets = secrets.filter { it.category == secret.category }
            .sortedBy { it.sortOrder }

        val index = categorySecrets.indexOfFirst { it.id == secretId }
        if (index < 0 || index >= categorySecrets.size - 1) return false

        val itemBelow = categorySecrets[index + 1]
        val newSortOrder = itemBelow.sortOrder
        val belowSortOrder = secret.sortOrder

        // Update both items
        val secretIndex = secrets.indexOfFirst { it.id == secretId }
        val belowIndex = secrets.indexOfFirst { it.id == itemBelow.id }

        secrets[secretIndex] = secrets[secretIndex].copy(sortOrder = newSortOrder)
        secrets[belowIndex] = secrets[belowIndex].copy(sortOrder = belowSortOrder)

        saveSecrets(secrets)
        return true
    }

    // MARK: - Enrollment Key

    /**
     * Get or create the enrollment public key entry.
     * This is a system field that cannot be deleted.
     */
    fun getEnrollmentPublicKey(): MinorSecret? {
        return getAllSecrets().find {
            it.isSystemField && it.type == SecretType.PUBLIC_KEY &&
                (it.name == "Identity Public Key" || it.name == "Enrollment Public Key") // Support old name for migration
        }
    }

    /**
     * Set the enrollment public key (called during enrollment or vault sync).
     * Creates or updates the system field.
     *
     * @param publicKeyBase64 The base64-encoded public key
     * @param keyType The key algorithm type (default: "Ed25519")
     */
    fun setEnrollmentPublicKey(publicKeyBase64: String, keyType: String = "Ed25519") {
        val secrets = getAllSecrets().toMutableList()
        // Check for both old and new names for migration support
        val existingIndex = secrets.indexOfFirst {
            it.isSystemField && it.type == SecretType.PUBLIC_KEY &&
                (it.name == "Identity Public Key" || it.name == "Enrollment Public Key")
        }

        val keyDescription = when (keyType) {
            "Ed25519" -> "Ed25519 identity public key for signature verification. Share to allow others to verify your signatures."
            "X25519" -> "X25519 public key for secure communication. Share to receive encrypted messages."
            else -> "$keyType public key."
        }

        val enrollmentSecret = MinorSecret(
            id = "identity_public_key",
            name = "Identity Public Key",
            value = publicKeyBase64,
            category = SecretCategory.CERTIFICATE,
            type = SecretType.PUBLIC_KEY,
            notes = keyDescription,
            isShareable = true,
            isInPublicProfile = true,  // Always in public profile
            isSystemField = true,       // Cannot be deleted
            sortOrder = -1,             // Always first
            syncStatus = SyncStatus.SYNCED, // Comes from vault, already synced
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        if (existingIndex >= 0) {
            secrets[existingIndex] = enrollmentSecret
        } else {
            secrets.add(0, enrollmentSecret)
        }

        saveSecrets(secrets)
        // No markPendingSync() - this comes from vault and is already synced
    }

    // MARK: - Clear

    /**
     * Clear all secrets.
     */
    fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }
}

// MARK: - Data Models

/**
 * A minor secret stored in the enclave datastore.
 */
data class MinorSecret(
    val id: String,
    val name: String,
    val value: String,
    val category: SecretCategory,
    val type: SecretType = SecretType.TEXT,          // Type of secret value
    val notes: String? = null,
    val isShareable: Boolean = true,
    val isInPublicProfile: Boolean = false,          // For PUBLIC_KEY types only
    val isSystemField: Boolean = false,              // For enrollment key (not deletable)
    val sortOrder: Int = 0,                          // Order within category
    val syncStatus: SyncStatus,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Categories for secrets.
 */
enum class SecretCategory(val displayName: String, val iconName: String) {
    // Financial
    CRYPTOCURRENCY("Cryptocurrency", "currency_bitcoin"),
    BANK_ACCOUNT("Bank Account", "account_balance"),
    CREDIT_CARD("Credit Card", "credit_card"),
    INSURANCE("Insurance", "health_and_safety"),
    // Identity Documents
    DRIVERS_LICENSE("Driver's License", "badge"),
    PASSPORT("Passport", "flight"),
    SSN("Social Security", "security"),
    // Technical
    API_KEY("API Key", "key"),
    PASSWORD("Password", "password"),
    WIFI("WiFi Credential", "wifi"),
    CERTIFICATE("Certificate", "verified_user"),
    NOTE("Secure Note", "note"),
    // Other
    OTHER("Other", "category")
}

/**
 * Types of secret values that define how they can be shared.
 */
enum class SecretType(val displayName: String, val canBePublic: Boolean) {
    PUBLIC_KEY("Public Key", true),           // Can be added to public profile
    PRIVATE_KEY("Private Key", false),        // NEVER shareable
    TOKEN("Token", false),
    PASSWORD("Password", false),
    PIN("PIN", false),
    ACCOUNT_NUMBER("Account Number", false),
    SEED_PHRASE("Seed Phrase", false),        // Crypto recovery - NEVER share
    TEXT("Text", false)                       // Generic text secret
}

/**
 * Sync status for secrets.
 */
enum class SyncStatus {
    PENDING,    // Not yet synced to vault
    SYNCED,     // Successfully synced
    CONFLICT,   // Conflict detected during sync
    ERROR       // Sync failed
}
