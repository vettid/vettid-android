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
     * @param notes Optional notes
     * @param isShareable Whether the secret can be shared
     * @return The created secret
     */
    fun addSecret(
        name: String,
        value: String,
        category: SecretCategory,
        notes: String? = null,
        isShareable: Boolean = true
    ): MinorSecret {
        val secret = MinorSecret(
            id = UUID.randomUUID().toString(),
            name = name,
            value = value,
            category = category,
            notes = notes,
            isShareable = isShareable,
            syncStatus = SyncStatus.PENDING,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val secrets = getAllSecrets().toMutableList()
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
                    "notes" to secret.notes,
                    "isShareable" to secret.isShareable,
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
                    notes = data["notes"] as? String,
                    isShareable = data["isShareable"] as? Boolean ?: true,
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
    val notes: String? = null,
    val isShareable: Boolean = true,
    val syncStatus: SyncStatus,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Categories for secrets.
 */
enum class SecretCategory(val displayName: String) {
    PASSWORD("Password"),
    API_KEY("API Key"),
    WIFI("WiFi Credential"),
    NOTE("Secure Note"),
    CERTIFICATE("Certificate"),
    OTHER("Other")
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
