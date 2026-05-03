package com.vettid.app.core.storage

import com.google.gson.Gson
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter for service contracts. In-memory only; no on-device persistence.
 *
 * Authoritative storage for service contracts lives in the vault under the
 * `service-contracts/<connectionID>/...` namespace, written by the
 * `service.contract.*` handlers. This class is currently only an in-memory
 * cache populated during a session.
 *
 * TODO: when the service-vault feature exits design phase, add vault verbs
 * for listing all signed contracts (`service.contract.list-all`) and
 * retrieving sealed connection material, and have `hydrate()` populate this
 * cache from the vault on PIN unlock. For now contracts only persist for
 * the lifetime of the process.
 */
@Singleton
class ContractStore @Inject constructor() {
    private val gson = Gson()

    private val contracts = ConcurrentHashMap<String, StoredContract>()
    private val natsCreds = ConcurrentHashMap<String, ServiceNatsCredentials>()
    private val connectionKeys = ConcurrentHashMap<String, ByteArray>()
    @Volatile private var lastSyncTime: Long = 0L

    // MARK: - Contract CRUD Operations

    fun save(contract: StoredContract) {
        contracts[contract.contractId] = contract
        android.util.Log.i("ContractStore", "Saved contract: ${contract.contractId} for service: ${contract.serviceId}")
    }

    fun get(contractId: String): StoredContract? = contracts[contractId]

    fun getByServiceId(serviceId: String): StoredContract? =
        contracts.values.firstOrNull { it.serviceId == serviceId }

    fun listAll(): List<StoredContract> = contracts.values.toList()

    fun listActive(): List<StoredContract> =
        contracts.values.filter { it.status == ContractStatus.ACTIVE }

    fun listByStatus(status: ContractStatus): List<StoredContract> =
        contracts.values.filter { it.status == status }

    fun delete(contractId: String) {
        contracts.remove(contractId)
        natsCreds.remove(contractId)
        connectionKeys.remove(contractId)
        android.util.Log.i("ContractStore", "Deleted contract: $contractId")
    }

    fun updateStatus(contractId: String, status: ContractStatus) {
        val c = contracts[contractId] ?: return
        save(c.copy(status = status, updatedAt = Instant.now()))
    }

    fun hasContractForService(serviceId: String): Boolean = getByServiceId(serviceId) != null

    // MARK: - Connection Key (in-memory only)

    fun storeConnectionKey(contractId: String, privateKey: ByteArray) {
        connectionKeys[contractId] = privateKey.copyOf()
        android.util.Log.i("ContractStore", "Stored connection key for contract: $contractId")
    }

    fun getConnectionKey(contractId: String): ByteArray? = connectionKeys[contractId]?.copyOf()

    // MARK: - NATS Credentials

    fun storeNatsCredentials(contractId: String, credentials: ServiceNatsCredentials) {
        natsCreds[contractId] = credentials
    }

    fun getNatsCredentials(contractId: String): ServiceNatsCredentials? = natsCreds[contractId]

    fun deleteNatsCredentials(contractId: String) { natsCreds.remove(contractId) }

    // MARK: - Sync Tracking

    fun updateLastSyncTime() { lastSyncTime = System.currentTimeMillis() }

    fun getLastSyncTime(): Instant? =
        if (lastSyncTime > 0) Instant.ofEpochMilli(lastSyncTime) else null

    fun clearAll() {
        // Wipe sensitive material before dropping references.
        connectionKeys.values.forEach { it.fill(0) }
        contracts.clear()
        natsCreds.clear()
        connectionKeys.clear()
        lastSyncTime = 0L
        android.util.Log.i("ContractStore", "Cleared all contracts")
    }

    fun getActiveContractCount(): Int = listActive().size

    /**
     * Populate the in-memory cache from vault on session start. Currently a
     * no-op until vault verbs for listing signed contracts are wired up;
     * see TODO at top of file.
     */
    @Suppress("RedundantSuspendModifier")
    suspend fun hydrate() { /* no-op until service-vault feature lands */ }
}

// MARK: - Data Classes

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

enum class ContractStatus {
    ACTIVE,
    PENDING_UPDATE,
    SUSPENDED,
    REVOKED,
    EXPIRED
}

data class ContractCapability(
    val type: CapabilityType,
    val description: String,
    val parameters: Map<String, Any> = emptyMap()
)

enum class CapabilityType {
    READ_DATA,
    WRITE_DATA,
    SEND_MESSAGES,
    REQUEST_AUTH,
    REQUEST_PAYMENT,
    SEND_NOTIFICATIONS
}

data class ServiceNatsCredentials(
    val endpoint: String,
    val userJwt: String,
    val userSeed: String,
    val subject: String,
    val expiresAt: Instant? = null
)
