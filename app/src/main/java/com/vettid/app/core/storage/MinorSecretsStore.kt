package com.vettid.app.core.storage

import com.google.gson.JsonObject
import com.vettid.app.core.nats.OwnerSpaceClient
import com.vettid.app.core.nats.VaultResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter for "minor" secrets — vault-backed, no on-device persistence.
 *
 * Storage model: every secret lives in the vault under the
 * `secrets/<id>::<alias?>` namespace, encrypted at rest by the
 * vault's EncryptedStorage DEK. This class is a thin client over the
 * `secret.*` NATS ops, exposed as suspend methods so callers compose
 * naturally inside `viewModelScope.launch { ... }`.
 *
 * Why no local store: writing user data to device storage was the
 * source of catalog inconsistency (badge counts, peer view drift,
 * "I added a secret but my peer never saw it"). The vault is now the
 * single source of truth for "what I have" — the device only sees
 * the credential blob, app prefs, and operational caches.
 *
 *   - Critical secrets stay sealed in the credential (require
 *     password to unseal). See `CredentialSecretHandler` on the
 *     vault and the existing critical-secret reveal flow.
 *   - Minor secrets live in `secrets/`, retrievable in any
 *     authenticated session via [revealSecretValue].
 *
 * The `publishDirtyTick` flow lets ViewModels know that something
 * changed so they can re-fetch state — fired after every mutation.
 */
@Singleton
class MinorSecretsStore @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
) {
    private val _publishDirtyTick = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val publishDirtyTick: kotlinx.coroutines.flow.StateFlow<Long> = _publishDirtyTick

    private fun bumpPublishDirty() {
        _publishDirtyTick.value = _publishDirtyTick.value + 1
    }

    // MARK: - Reads (metadata)

    /**
     * Fetch every secret's metadata from the vault. Values are
     * absent — call [revealSecretValue] to fetch a single value
     * on demand.
     */
    suspend fun getAllSecrets(): List<MinorSecret> {
        val resp = ownerSpaceClient.sendAndAwaitResponse(
            "secret.list", JsonObject(), 10_000L
        ) as? VaultResponse.HandlerResult ?: return emptyList()
        if (!resp.success || resp.result == null) return emptyList()
        val arr = resp.result.getAsJsonArray("secrets") ?: return emptyList()
        val out = mutableListOf<MinorSecret>()
        for (el in arr) {
            try {
                val o = el.asJsonObject
                out += parseSummary(o)
            } catch (_: Exception) { /* skip */ }
        }
        return out
    }

    /**
     * Get a single secret's metadata (no value). Used by edit /
     * detail views that need name + category + flags but should not
     * pull the value into UI state.
     */
    suspend fun getSecret(id: String): MinorSecret? {
        return getAllSecrets().firstOrNull { it.id == id }
    }

    /**
     * Reveal the secret value. Hits `secret.get` on the vault. No
     * password re-prompt — if the vault is unsealed, the value is
     * retrievable. (That's the line between minor and critical.)
     */
    suspend fun revealSecretValue(id: String): String? {
        val payload = JsonObject().apply { addProperty("id", id) }
        val resp = ownerSpaceClient.sendAndAwaitResponse(
            "secret.get", payload, 10_000L
        ) as? VaultResponse.HandlerResult ?: return null
        if (!resp.success || resp.result == null) return null
        return resp.result.get("value")?.takeIf { !it.isJsonNull }?.asString
    }

    suspend fun searchSecrets(query: String): List<MinorSecret> {
        if (query.isBlank()) return getAllSecrets()
        return getAllSecrets().filter {
            it.name.contains(query, ignoreCase = true) ||
                it.notes?.contains(query, ignoreCase = true) == true
        }
    }

    suspend fun getSecretsByCategory(category: SecretCategory): List<MinorSecret> {
        return getAllSecrets().filter { it.category == category }
    }

    /**
     * Subset shown on the calling card — public-key secrets the user
     * has explicitly opted into the public profile.
     */
    suspend fun getPublicProfileSecrets(): List<MinorSecret> {
        return getAllSecrets().filter { it.isInPublicProfile }
    }

    // MARK: - Writes

    /**
     * Add a new minor secret. Calls `secret.add` on the vault and
     * returns the freshly-stored record (with its vault-assigned ID).
     */
    suspend fun addSecret(
        name: String,
        value: String,
        category: SecretCategory,
        type: SecretType = SecretType.TEXT,
        notes: String? = null,
        isShareable: Boolean = true,
        isInPublicProfile: Boolean = false,
        isSystemField: Boolean = false,
        groupId: String? = null,
        groupLabel: String? = null,
        alias: String? = null,
    ): MinorSecret? {
        val payload = JsonObject().apply {
            addProperty("name", name)
            addProperty("value", value)
            addProperty("category", category.name)
            addProperty("type", type.name)
            if (!alias.isNullOrBlank()) addProperty("alias", alias)
            if (!notes.isNullOrBlank()) addProperty("description", notes)
            // New entries default to cataloged — peers see metadata,
            // values stay in the vault. Toggling to public puts the
            // metadata on the calling card; toggling to private hides
            // it from peers entirely.
            addProperty(
                "discoverability",
                if (isInPublicProfile) "public" else "cataloged",
            )
        }
        val resp = ownerSpaceClient.sendAndAwaitResponse(
            "secret.add", payload, 10_000L
        ) as? VaultResponse.HandlerResult ?: return null
        if (!resp.success || resp.result == null) return null
        bumpPublishDirty()
        val id = resp.result.get("id")?.asString ?: return null
        // Re-fetch to populate the full record with vault-assigned
        // timestamps; cheap and keeps the return shape canonical.
        return getSecret(id)
    }

    suspend fun updateSecret(secret: MinorSecret): Boolean {
        val payload = JsonObject().apply {
            addProperty("id", secret.id)
            addProperty("name", secret.name)
            addProperty("value", secret.value)
            addProperty("category", secret.category.name)
            addProperty("type", secret.type.name)
            if (!secret.notes.isNullOrBlank()) addProperty("description", secret.notes)
        }
        val ok = sendOk("secret.update", payload)
        if (ok) bumpPublishDirty()
        return ok
    }

    suspend fun deleteSecret(id: String): Boolean {
        val payload = JsonObject().apply { addProperty("id", id) }
        val ok = sendOk("secret.delete", payload)
        if (ok) bumpPublishDirty()
        return ok
    }

    /**
     * Flip "show on calling card" → public discoverability. Returns
     * false when the operation isn't valid (e.g. category isn't a
     * public-key type — server-side check).
     */
    suspend fun togglePublicProfile(id: String): Boolean {
        val current = getSecret(id) ?: return false
        val next = if (current.isInPublicProfile) "cataloged" else "public"
        return setDiscoverability(id, next)
    }

    /**
     * Flip the "hide from catalog" axis: private vs cataloged.
     */
    suspend fun toggleHideFromCatalog(id: String): Boolean {
        val current = getSecret(id) ?: return false
        val next = if (current.hideFromCatalog) "cataloged" else "private"
        return setDiscoverability(id, next)
    }

    /**
     * Update the group label across every secret sharing a
     * `groupId`. Each entry is updated individually since the
     * vault's `secret.update` operates per-record.
     */
    suspend fun updateGroupLabel(groupId: String, newLabel: String) {
        val all = getAllSecrets().filter { it.groupId == groupId }
        for (s in all) {
            updateSecret(s.copy(groupLabel = newLabel, updatedAt = System.currentTimeMillis()))
        }
    }

    // MARK: - Internal helpers

    private suspend fun setDiscoverability(id: String, value: String): Boolean {
        val payload = JsonObject().apply {
            addProperty("id", id)
            addProperty("discoverability", value)
        }
        val ok = sendOk("secret.set-discoverability", payload)
        if (ok) bumpPublishDirty()
        return ok
    }

    private suspend fun sendOk(messageType: String, payload: JsonObject): Boolean {
        val resp = ownerSpaceClient.sendAndAwaitResponse(messageType, payload, 10_000L)
        return resp is VaultResponse.HandlerResult && resp.success
    }

    private fun parseSummary(o: JsonObject): MinorSecret {
        val id = o.get("id")?.asString ?: ""
        val name = o.get("name")?.asString ?: ""
        val categoryStr = o.get("category")?.asString.orEmpty()
        val category = runCatching { SecretCategory.valueOf(categoryStr) }
            .getOrDefault(SecretCategory.OTHER)
        val typeStr = o.get("type")?.asString.orEmpty()
        val type = runCatching { SecretType.valueOf(typeStr) }
            .getOrDefault(SecretType.TEXT)
        val discoverability = o.get("discoverability")?.asString.orEmpty()
        val createdAt = o.get("created_at")?.asString.orEmpty()
        val updatedAt = o.get("updated_at")?.asString.orEmpty()
        return MinorSecret(
            id = id,
            name = name,
            value = "", // values fetched on demand via revealSecretValue
            category = category,
            type = type,
            alias = o.get("alias")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
            notes = o.get("description")?.asString,
            isShareable = true,
            isInPublicProfile = discoverability == "public",
            hideFromCatalog = discoverability == "private",
            isSystemField = false,
            sortOrder = 0,
            syncStatus = SyncStatus.SYNCED,
            createdAt = parseIsoMillis(createdAt),
            updatedAt = parseIsoMillis(updatedAt),
        )
    }

    private fun parseIsoMillis(s: String): Long = try {
        if (s.isEmpty()) System.currentTimeMillis()
        else java.time.Instant.parse(s).toEpochMilli()
    } catch (_: Exception) { System.currentTimeMillis() }
}

// MARK: - Data Models
//
// Re-declared here so callers continue to use the existing imports
// after the store rewrite. The shape is unchanged from the local-only
// era; only the storage backend moved.

data class MinorSecret(
    val id: String,
    val name: String,
    val value: String,
    val category: SecretCategory,
    val type: SecretType = SecretType.TEXT,
    val alias: String = "",
    val notes: String? = null,
    val isShareable: Boolean = true,
    val isInPublicProfile: Boolean = false,
    val hideFromCatalog: Boolean = false,
    val isSystemField: Boolean = false,
    val sortOrder: Int = 0,
    val groupId: String? = null,
    val groupLabel: String? = null,
    val syncStatus: SyncStatus,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class SecretCategory(val displayName: String, val iconName: String) {
    IDENTITY("Identity", "fingerprint"),
    CRYPTOCURRENCY("Cryptocurrency", "currency_bitcoin"),
    BANK_ACCOUNT("Bank Account", "account_balance"),
    CREDIT_CARD("Credit Card", "credit_card"),
    INSURANCE("Insurance", "health_and_safety"),
    DRIVERS_LICENSE("Driver's License", "badge"),
    PASSPORT("Passport", "flight"),
    SSN("Social Security", "security"),
    API_KEY("API Key", "key"),
    PASSWORD("Password", "password"),
    WIFI("WiFi Credential", "wifi"),
    CERTIFICATE("Certificate", "verified_user"),
    NOTE("Secure Note", "note"),
    LOGIN("Login Credential", "login"),
    TOTP("Authenticator", "timer"),
    SOFTWARE_LICENSE("Software License", "key"),
    VPN("VPN", "vpn_key"),
    SSH("SSH Key", "terminal"),
    VEHICLE("Vehicle", "directions_car"),
    LOYALTY("Loyalty/Rewards", "card_giftcard"),
    TAX("Tax", "receipt_long"),
    OTHER("Other", "category"),
}

enum class SecretType(val displayName: String, val canBePublic: Boolean) {
    PUBLIC_KEY("Public Key", true),
    PRIVATE_KEY("Private Key", false),
    TOKEN("Token", false),
    PASSWORD("Password", false),
    PIN("PIN", false),
    ACCOUNT_NUMBER("Account Number", false),
    SEED_PHRASE("Seed Phrase", false),
    TEXT("Text", false),
}

enum class SyncStatus {
    PENDING,
    SYNCED,
    CONFLICT,
    ERROR,
}
