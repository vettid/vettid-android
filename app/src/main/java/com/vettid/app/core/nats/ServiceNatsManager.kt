package com.vettid.app.core.nats

import com.vettid.app.core.crypto.EncryptedServiceMessage
import com.vettid.app.core.crypto.ServiceMessageCrypto
import com.vettid.app.core.storage.ContractStore
import com.vettid.app.core.storage.StoredContract
import com.vettid.app.core.storage.ContractStatus
import com.vettid.app.core.storage.ServiceNatsCredentials
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages multiple NATS connections for service vaults.
 *
 * Each service connection has its own NATS endpoint and credentials.
 * This manager handles:
 * - Multi-cluster connection management
 * - Automatic reconnection on failure
 * - Message routing to/from services
 * - Connection health monitoring
 *
 * Issue #29 [AND-040] - Service NATS connection manager implementation.
 */
@Singleton
class ServiceNatsManager @Inject constructor(
    private val contractStore: ContractStore,
    private val serviceCrypto: ServiceMessageCrypto
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    // Active connections by contract ID
    private val connections = ConcurrentHashMap<String, ServiceNatsConnection>()
    private val connectionJobs = ConcurrentHashMap<String, Job>()

    // Connection state per service
    private val _connectionStates = MutableStateFlow<Map<String, ServiceConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, ServiceConnectionState>> = _connectionStates.asStateFlow()

    // Incoming messages from all services
    private val _incomingMessages = MutableSharedFlow<ServiceMessage>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incomingMessages: SharedFlow<ServiceMessage> = _incomingMessages.asSharedFlow()

    // Approval requests (auth/authz) from services
    private val _approvalRequests = MutableSharedFlow<ApprovalRequest>(
        replay = 0,
        extraBufferCapacity = 50,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val approvalRequests: SharedFlow<ApprovalRequest> = _approvalRequests.asSharedFlow()

    companion object {
        private const val TAG = "ServiceNatsManager"
        private const val RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_ATTEMPTS = -1 // Unlimited
    }

    // MARK: - Connection Management

    /**
     * Connect to all active service NATS endpoints.
     * Called on app startup.
     */
    suspend fun connectAll() {
        val activeContracts = contractStore.listActive()
        android.util.Log.i(TAG, "Connecting to ${activeContracts.size} service endpoints")

        activeContracts.forEach { contract ->
            connect(contract)
        }
    }

    /**
     * Connect to a specific service's NATS endpoint.
     */
    suspend fun connect(contract: StoredContract) = mutex.withLock {
        val contractId = contract.contractId

        // Skip if already connected
        if (connections.containsKey(contractId)) {
            android.util.Log.d(TAG, "Already connected to service: $contractId")
            return
        }

        // Get NATS credentials
        val credentials = contractStore.getNatsCredentials(contractId)
        if (credentials == null) {
            android.util.Log.w(TAG, "No NATS credentials for contract: $contractId")
            updateState(contractId, ServiceConnectionState.Error("No credentials"))
            return
        }

        updateState(contractId, ServiceConnectionState.Connecting)

        // Create connection
        val connection = ServiceNatsConnection(
            contractId = contractId,
            serviceId = contract.serviceId,
            serviceName = contract.serviceName,
            credentials = credentials
        )

        // Start connection in background
        val job = scope.launch {
            try {
                connection.connect()
                connections[contractId] = connection
                updateState(contractId, ServiceConnectionState.Connected)
                android.util.Log.i(TAG, "Connected to service: ${contract.serviceName}")

                // Start message listener
                connection.startMessageListener { message ->
                    handleIncomingMessage(contractId, message)
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to connect to service: $contractId", e)
                updateState(contractId, ServiceConnectionState.Error(e.message ?: "Connection failed"))

                // Schedule reconnection
                scheduleReconnect(contract)
            }
        }

        connectionJobs[contractId] = job
    }

    /**
     * Disconnect from a specific service.
     */
    suspend fun disconnect(serviceId: String) = mutex.withLock {
        // Find contract by service ID
        val contract = contractStore.getByServiceId(serviceId)
        val contractId = contract?.contractId ?: return

        disconnectByContractId(contractId)
    }

    /**
     * Disconnect by contract ID.
     */
    private suspend fun disconnectByContractId(contractId: String) {
        connectionJobs[contractId]?.cancel()
        connectionJobs.remove(contractId)

        connections[contractId]?.let { conn ->
            conn.disconnect()
            connections.remove(contractId)
            updateState(contractId, ServiceConnectionState.Disconnected)
            android.util.Log.i(TAG, "Disconnected from service: $contractId")
        }
    }

    /**
     * Disconnect from all services.
     */
    suspend fun disconnectAll() = mutex.withLock {
        android.util.Log.i(TAG, "Disconnecting from all services")

        connectionJobs.values.forEach { it.cancel() }
        connectionJobs.clear()

        connections.values.forEach { it.disconnect() }
        connections.clear()

        _connectionStates.value = emptyMap()
    }

    // MARK: - Message Publishing

    /**
     * Publish a message to a service.
     */
    suspend fun publish(
        serviceId: String,
        subject: String,
        data: ByteArray
    ): Result<Unit> {
        val contract = contractStore.getByServiceId(serviceId)
            ?: return Result.failure(ServiceNatsError.NotConnected(serviceId))

        val connection = connections[contract.contractId]
            ?: return Result.failure(ServiceNatsError.NotConnected(serviceId))

        return try {
            connection.publish(subject, data)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(ServiceNatsError.PublishFailed(serviceId, e.message))
        }
    }

    /**
     * Publish an encrypted message to a service.
     */
    suspend fun publishEncrypted(
        serviceId: String,
        subject: String,
        message: EncryptedServiceMessage
    ): Result<Unit> {
        val json = com.google.gson.Gson().toJson(message)
        return publish(serviceId, subject, json.toByteArray(Charsets.UTF_8))
    }

    /**
     * Request-reply pattern with a service.
     */
    suspend fun request(
        serviceId: String,
        subject: String,
        data: ByteArray,
        timeoutMs: Long = 30000
    ): Result<ByteArray> {
        val contract = contractStore.getByServiceId(serviceId)
            ?: return Result.failure(ServiceNatsError.NotConnected(serviceId))

        val connection = connections[contract.contractId]
            ?: return Result.failure(ServiceNatsError.NotConnected(serviceId))

        return try {
            val response = connection.request(subject, data, timeoutMs)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(ServiceNatsError.RequestFailed(serviceId, e.message))
        }
    }

    // MARK: - Message Handling

    /**
     * Handle incoming message from a service.
     */
    private fun handleIncomingMessage(contractId: String, rawMessage: ByteArray) {
        scope.launch {
            try {
                val contract = contractStore.get(contractId) ?: return@launch

                // Parse message type
                val messageWrapper = parseMessageWrapper(rawMessage)

                when (messageWrapper.type) {
                    "auth_request" -> {
                        val request = parseApprovalRequest(
                            contractId,
                            contract.serviceId,
                            contract.serviceName,
                            ApprovalRequestType.AUTHENTICATION,
                            messageWrapper.payload
                        )
                        _approvalRequests.emit(request)
                    }

                    "authz_request" -> {
                        val request = parseApprovalRequest(
                            contractId,
                            contract.serviceId,
                            contract.serviceName,
                            ApprovalRequestType.AUTHORIZATION,
                            messageWrapper.payload
                        )
                        _approvalRequests.emit(request)
                    }

                    else -> {
                        // General message
                        _incomingMessages.emit(
                            ServiceMessage(
                                contractId = contractId,
                                serviceId = contract.serviceId,
                                serviceName = contract.serviceName,
                                type = messageWrapper.type,
                                payload = messageWrapper.payload
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to handle message from $contractId", e)
            }
        }
    }

    private fun parseMessageWrapper(data: ByteArray): MessageWrapper {
        val json = String(data, Charsets.UTF_8)
        return com.google.gson.Gson().fromJson(json, MessageWrapper::class.java)
    }

    private fun parseApprovalRequest(
        contractId: String,
        serviceId: String,
        serviceName: String,
        type: ApprovalRequestType,
        payload: String
    ): ApprovalRequest {
        val gson = com.google.gson.Gson()
        val requestData = gson.fromJson(payload, ApprovalRequestData::class.java)

        return ApprovalRequest(
            requestId = requestData.requestId,
            contractId = contractId,
            serviceId = serviceId,
            serviceName = serviceName,
            type = type,
            purpose = requestData.purpose,
            action = requestData.action,
            resource = requestData.resource,
            context = requestData.context,
            expiresAt = requestData.expiresAt
        )
    }

    // MARK: - Connection State

    private fun updateState(contractId: String, state: ServiceConnectionState) {
        val currentStates = _connectionStates.value.toMutableMap()
        currentStates[contractId] = state
        _connectionStates.value = currentStates
    }

    /**
     * Check if connected to a service.
     */
    fun isConnected(serviceId: String): Boolean {
        val contract = contractStore.getByServiceId(serviceId) ?: return false
        return connections.containsKey(contract.contractId)
    }

    /**
     * Get connection state for a service.
     */
    fun getConnectionState(serviceId: String): ServiceConnectionState {
        val contract = contractStore.getByServiceId(serviceId)
            ?: return ServiceConnectionState.Disconnected
        return _connectionStates.value[contract.contractId] ?: ServiceConnectionState.Disconnected
    }

    // MARK: - Reconnection

    private fun scheduleReconnect(contract: StoredContract) {
        scope.launch {
            kotlinx.coroutines.delay(RECONNECT_DELAY_MS)

            // Check if contract is still active
            val current = contractStore.get(contract.contractId)
            if (current?.status == ContractStatus.ACTIVE) {
                android.util.Log.i(TAG, "Attempting reconnection to: ${contract.serviceName}")
                connect(current)
            }
        }
    }

    // MARK: - Health Check

    /**
     * Check health of all connections.
     */
    suspend fun checkHealth(): Map<String, Boolean> {
        val healthMap = mutableMapOf<String, Boolean>()

        connections.forEach { (contractId, connection) ->
            healthMap[contractId] = connection.isHealthy()
        }

        return healthMap
    }
}

// MARK: - Supporting Classes

/**
 * Individual service NATS connection.
 */
class ServiceNatsConnection(
    val contractId: String,
    val serviceId: String,
    val serviceName: String,
    private val credentials: ServiceNatsCredentials
) {
    private var client: AndroidNatsClient? = null
    private var isConnected = false
    private val connectionScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    suspend fun connect() {
        val natsClient = AndroidNatsClient()
        natsClient.connect(
            endpoint = credentials.endpoint,
            jwt = credentials.userJwt,
            seed = credentials.userSeed
        ).getOrThrow()
        client = natsClient
        isConnected = true
    }

    suspend fun disconnect() {
        client?.disconnect()
        isConnected = false
        client = null
    }

    suspend fun publish(subject: String, data: ByteArray) {
        client?.publish(subject, data)?.getOrThrow()
            ?: throw ServiceNatsError.NotConnected(serviceId)
    }

    suspend fun request(subject: String, data: ByteArray, timeoutMs: Long): ByteArray {
        val result = client?.request(subject, data, timeoutMs)?.getOrThrow()
            ?: throw ServiceNatsError.NotConnected(serviceId)
        return result.data
    }

    fun startMessageListener(handler: (ByteArray) -> Unit) {
        connectionScope.launch {
            client?.subscribe(credentials.subject) { message ->
                handler(message.data)
            }
        }
    }

    fun isHealthy(): Boolean {
        return isConnected && client != null
    }
}

/**
 * Connection state for a service.
 */
sealed class ServiceConnectionState {
    object Disconnected : ServiceConnectionState()
    object Connecting : ServiceConnectionState()
    object Connected : ServiceConnectionState()
    object Reconnecting : ServiceConnectionState()
    data class Error(val message: String) : ServiceConnectionState()
}

/**
 * Message from a service.
 */
data class ServiceMessage(
    val contractId: String,
    val serviceId: String,
    val serviceName: String,
    val type: String,
    val payload: String
)

/**
 * Approval request from a service (auth or authz).
 */
data class ApprovalRequest(
    val requestId: String,
    val contractId: String,
    val serviceId: String,
    val serviceName: String,
    val type: ApprovalRequestType,
    val purpose: String,
    val action: String? = null,
    val resource: String? = null,
    val context: Map<String, Any> = emptyMap(),
    val expiresAt: Long
)

/**
 * Type of approval request.
 */
enum class ApprovalRequestType {
    AUTHENTICATION,
    AUTHORIZATION
}

/**
 * Wrapper for incoming messages.
 */
private data class MessageWrapper(
    val type: String,
    val payload: String
)

/**
 * Data structure for approval requests.
 */
private data class ApprovalRequestData(
    val requestId: String,
    val purpose: String,
    val action: String? = null,
    val resource: String? = null,
    val context: Map<String, Any> = emptyMap(),
    val expiresAt: Long
)

/**
 * Service NATS errors.
 */
sealed class ServiceNatsError(message: String) : Exception(message) {
    class NotConnected(serviceId: String) : ServiceNatsError("Not connected to service: $serviceId")
    class PublishFailed(serviceId: String, details: String?) : ServiceNatsError("Publish failed for $serviceId: $details")
    class RequestFailed(serviceId: String, details: String?) : ServiceNatsError("Request failed for $serviceId: $details")
}
