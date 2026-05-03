package com.vettid.app.core.storage

import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter for critical-secret metadata. Vault-backed; no on-device
 * persistence.
 *
 * Critical secrets themselves live sealed inside the credential
 * blob. The metadata index (`credential-secrets/_metadata`) is
 * authoritative on the vault and is what `credential.secret.list`
 * returns. This adapter lets existing callers stay on the same API
 * (`getAllMetadata`, `getMetadata`, `storeMetadata`, …) while every
 * read goes to the vault and every write is a no-op (the actual
 * write happens on the `credential.secret.*` path).
 *
 * The local access-log is dropped — it was never used elsewhere and
 * counted as on-device user data.
 */
@Singleton
class CriticalSecretMetadataStore @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) {

    suspend fun getAllMetadata(): List<CriticalSecretMetadata> {
        val resp = ownerSpaceClient.sendAndAwaitResponse(
            "credential.secret.list", JsonObject(), 10_000L
        ) as? VaultResponse.HandlerResult ?: return emptyList()
        if (!resp.success || resp.result == null) return emptyList()
        val arr = resp.result.getAsJsonArray("secrets") ?: return emptyList()
        val out = mutableListOf<CriticalSecretMetadata>()
        for (el in arr) {
            try {
                val o = el.asJsonObject
                out += parseMetadata(o)
            } catch (_: Exception) { /* skip malformed */ }
        }
        return out
    }

    suspend fun getMetadata(id: String): CriticalSecretMetadata? =
        getAllMetadata().firstOrNull { it.id == id }

    /**
     * Local writes were a duplicate index. The credential-secret
     * write path on the vault (credential.secret.add) is the
     * authoritative store; this is a no-op compatibility shim.
     */
    @Suppress("UNUSED_PARAMETER")
    fun storeMetadata(metadata: CriticalSecretMetadata) { /* no-op */ }

    @Suppress("UNUSED_PARAMETER")
    fun removeMetadata(id: String) { /* no-op */ }

    /**
     * Access tracking moves to the vault's audit log when needed.
     * Local-only access counters were never read elsewhere.
     */
    @Suppress("UNUSED_PARAMETER")
    fun recordAccess(id: String) { /* no-op */ }

    fun getAccessLog(): List<CriticalSecretAccessLog> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun getAccessLogForSecret(secretId: String): List<CriticalSecretAccessLog> = emptyList()

    @Suppress("UNUSED_PARAMETER")
    fun importFromVault(metadataList: List<Map<String, Any?>>) { /* no-op */ }

    fun getLastSyncedAt(): Long = System.currentTimeMillis()

    fun clearAll() { /* no-op */ }

    // --- Helpers ---

    private fun parseMetadata(o: JsonObject): CriticalSecretMetadata {
        val id = o.get("id")?.asString.orEmpty()
        val name = o.get("name")?.asString.orEmpty()
        val categoryStr = o.get("category")?.asString.orEmpty()
        val category = runCatching { CriticalSecretCategory.valueOf(categoryStr) }
            .getOrDefault(CriticalSecretCategory.OTHER)
        val description = o.get("description")?.takeIf { !it.isJsonNull }?.asString
        val createdAt = o.get("created_at")?.asString?.let {
            runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrDefault(0L)
        } ?: 0L
        return CriticalSecretMetadata(
            id = id,
            name = name,
            category = category,
            description = description,
            createdAt = createdAt,
            lastAccessedAt = null,
            accessCount = 0,
        )
    }
}

// MARK: - Data Models

data class CriticalSecretMetadata(
    val id: String,
    val name: String,
    val category: CriticalSecretCategory,
    val description: String?,
    val createdAt: Long,
    val lastAccessedAt: Long?,
    val accessCount: Int,
)

enum class CriticalSecretCategory(val displayName: String) {
    SEED_PHRASE("Seed Phrase"),
    PRIVATE_KEY("Private Key"),
    SIGNING_KEY("Signing Key"),
    MASTER_PASSWORD("Master Password"),
    RECOVERY_KEY("Recovery Key"),
    OTHER("Critical Secret"),
}

data class CriticalSecretAccessLog(
    val secretId: String,
    val accessedAt: Long,
)

sealed class CriticalSecretViewState {
    object Hidden : CriticalSecretViewState()
    object PasswordPrompt : CriticalSecretViewState()
    data class AcknowledgementRequired(
        val secretId: String,
        val secretName: String,
    ) : CriticalSecretViewState()
    data class Retrieving(val progress: Float = 0f) : CriticalSecretViewState()
    data class Revealed(
        val value: String,
        val expiresInSeconds: Int,
    ) : CriticalSecretViewState()
    data class Error(val message: String) : CriticalSecretViewState()
}
