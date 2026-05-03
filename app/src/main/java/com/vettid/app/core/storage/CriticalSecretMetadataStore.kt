package com.vettid.app.core.storage

import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Read-only adapter for critical-secret metadata. The vault is the
 * authoritative store; writes go directly through `credential.secret.*`
 * verbs on `OwnerSpaceClient`.
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
