package com.vettid.app.features.debug

import android.util.Base64
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.vettid.app.core.nats.*
import com.vettid.app.core.storage.CredentialStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

private const val TAG = "CredentialDebug"

/**
 * Debug screen for testing credential.create and credential.unseal operations.
 *
 * This screen is for development/testing purposes only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CredentialDebugScreen(
    onBack: () -> Unit,
    viewModel: CredentialDebugViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Credential Debug") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState)
        ) {
            // Connection Status
            ConnectionStatusCard(
                isConnected = state.isNatsConnected,
                isE2EEnabled = state.isE2EEnabled,
                ownerSpaceId = state.ownerSpaceId
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Create Credential Section
            CreateCredentialCard(
                pin = state.pin,
                onPinChange = viewModel::updatePin,
                isLoading = state.isCreating,
                onCreateClick = viewModel::createCredential,
                enabled = state.isNatsConnected
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Unseal Credential Section
            UnsealCredentialCard(
                sealedCredential = state.sealedCredential,
                challengePin = state.challengePin,
                onChallengePinChange = viewModel::updateChallengePin,
                isLoading = state.isUnsealing,
                onUnsealClick = viewModel::unsealCredential,
                enabled = state.isNatsConnected && state.sealedCredential.isNotEmpty()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Results Section
            if (state.lastResult.isNotEmpty()) {
                ResultCard(
                    title = "Last Result",
                    content = state.lastResult,
                    isError = state.isError
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Debug Log
            if (state.debugLog.isNotEmpty()) {
                DebugLogCard(log = state.debugLog)
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    isConnected: Boolean,
    isE2EEnabled: Boolean,
    ownerSpaceId: String?
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isConnected) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isConnected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isConnected) "NATS Connected" else "NATS Disconnected",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row {
                Text("E2E: ", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = if (isE2EEnabled) "Enabled" else "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isE2EEnabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.error
                )
            }

            ownerSpaceId?.let {
                Text(
                    text = "OwnerSpace: ${it.take(20)}...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
private fun CreateCredentialCard(
    pin: String,
    onPinChange: (String) -> Unit,
    isLoading: Boolean,
    onCreateClick: () -> Unit,
    enabled: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Create Credential",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Creates a new Protean Credential sealed with KMS",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = pin,
                onValueChange = onPinChange,
                label = { Text("PIN (4-6 digits)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isLoading,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onCreateClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isLoading && pin.length >= 4
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Create Credential")
            }
        }
    }
}

@Composable
private fun UnsealCredentialCard(
    sealedCredential: String,
    challengePin: String,
    onChallengePinChange: (String) -> Unit,
    isLoading: Boolean,
    onUnsealClick: () -> Unit,
    enabled: Boolean
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Unseal Credential",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Unseals the credential to get a session token",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (sealedCredential.isNotEmpty()) {
                Text(
                    text = "Sealed credential: ${sealedCredential.take(40)}...",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "No sealed credential. Create one first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = challengePin,
                onValueChange = onChallengePinChange,
                label = { Text("PIN Challenge") },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isLoading,
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onUnsealClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !isLoading && challengePin.length >= 4
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text("Unseal Credential")
            }
        }
    }
}

@Composable
private fun ResultCard(
    title: String,
    content: String,
    isError: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun DebugLogCard(log: String) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Debug Log",
                style = MaterialTheme.typography.titleSmall
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = log,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 20
            )
        }
    }
}

// ViewModel

data class CredentialDebugState(
    val isNatsConnected: Boolean = false,
    val isE2EEnabled: Boolean = false,
    val ownerSpaceId: String? = null,
    val pin: String = "",
    val challengePin: String = "",
    val sealedCredential: String = "",
    val sessionToken: String = "",
    val isCreating: Boolean = false,
    val isUnsealing: Boolean = false,
    val lastResult: String = "",
    val isError: Boolean = false,
    val debugLog: String = ""
)

@HiltViewModel
class CredentialDebugViewModel @Inject constructor(
    private val connectionManager: NatsConnectionManager,
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore
) : ViewModel() {

    private val _state = MutableStateFlow(CredentialDebugState())
    val state: StateFlow<CredentialDebugState> = _state.asStateFlow()

    private val pendingRequests = mutableMapOf<String, String>()

    init {
        refreshConnectionState()
        observeVaultResponses()
    }

    private fun refreshConnectionState() {
        viewModelScope.launch {
            val isConnected = connectionManager.isConnected()
            val ownerSpaceId = connectionManager.getOwnerSpaceId()

            _state.update {
                it.copy(
                    isNatsConnected = isConnected,
                    isE2EEnabled = ownerSpaceClient.isE2EEnabled,
                    ownerSpaceId = ownerSpaceId
                )
            }

            appendLog("Connection: connected=$isConnected, E2E=${ownerSpaceClient.isE2EEnabled}")
        }
    }

    private fun observeVaultResponses() {
        viewModelScope.launch {
            ownerSpaceClient.vaultResponses.collect { response ->
                handleVaultResponse(response)
            }
        }
    }

    private fun handleVaultResponse(response: VaultResponse) {
        val requestType = pendingRequests.remove(response.requestId) ?: return

        when (response) {
            is VaultResponse.HandlerResult -> {
                if (response.success && response.result != null) {
                    when (requestType) {
                        "credential.create" -> handleCreateResponse(response.result)
                        "credential.unseal" -> handleUnsealResponse(response.result)
                    }
                } else {
                    _state.update {
                        it.copy(
                            isCreating = false,
                            isUnsealing = false,
                            lastResult = "Error: ${response.error ?: "Unknown error"}",
                            isError = true
                        )
                    }
                    appendLog("Handler error: ${response.error}")
                }
            }
            is VaultResponse.Error -> {
                _state.update {
                    it.copy(
                        isCreating = false,
                        isUnsealing = false,
                        lastResult = "Error ${response.code}: ${response.message}",
                        isError = true
                    )
                }
                appendLog("Error response: ${response.code} - ${response.message}")
            }
            else -> {
                appendLog("Unexpected response type: $response")
            }
        }
    }

    private fun handleCreateResponse(result: JsonObject) {
        val credential = result.get("credential")?.asString
        val algorithm = result.get("algorithm")?.asString ?: "unknown"

        if (credential != null) {
            _state.update {
                it.copy(
                    isCreating = false,
                    sealedCredential = credential,
                    lastResult = "Credential created!\nAlgorithm: $algorithm\nBlob: ${credential.take(60)}...",
                    isError = false
                )
            }
            appendLog("Created credential with algorithm: $algorithm")
        } else {
            _state.update {
                it.copy(
                    isCreating = false,
                    lastResult = "Error: No credential in response",
                    isError = true
                )
            }
        }
    }

    private fun handleUnsealResponse(result: JsonObject) {
        val unsealResult = result.getAsJsonObject("unseal_result")
        val sessionToken = unsealResult?.get("session_token")?.asString
        val expiresAt = unsealResult?.get("expires_at")?.asLong

        if (sessionToken != null) {
            _state.update {
                it.copy(
                    isUnsealing = false,
                    sessionToken = sessionToken,
                    lastResult = "Credential unsealed!\nSession: ${sessionToken.take(40)}...\nExpires: $expiresAt",
                    isError = false
                )
            }
            appendLog("Unsealed credential, session expires: $expiresAt")
        } else {
            _state.update {
                it.copy(
                    isUnsealing = false,
                    lastResult = "Error: No session token in response",
                    isError = true
                )
            }
        }
    }

    fun updatePin(pin: String) {
        _state.update { it.copy(pin = pin.filter { c -> c.isDigit() }.take(6)) }
    }

    fun updateChallengePin(pin: String) {
        _state.update { it.copy(challengePin = pin.filter { c -> c.isDigit() }.take(6)) }
    }

    fun createCredential() {
        viewModelScope.launch {
            _state.update { it.copy(isCreating = true, lastResult = "", isError = false) }

            val pin = _state.value.pin
            appendLog("Creating credential with PIN: ${pin.take(2)}**")

            // Encode PIN as base64 (in production, this would be encrypted)
            val encryptedPin = Base64.encodeToString(pin.toByteArray(), Base64.NO_WRAP)

            val result = ownerSpaceClient.createCredential(
                encryptedPin = encryptedPin,
                authType = "pin"
            )

            result.fold(
                onSuccess = { requestId ->
                    pendingRequests[requestId] = "credential.create"
                    appendLog("Sent create request: $requestId")
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isCreating = false,
                            lastResult = "Failed to send: ${error.message}",
                            isError = true
                        )
                    }
                    appendLog("Send failed: ${error.message}")
                }
            )
        }
    }

    fun unsealCredential() {
        viewModelScope.launch {
            _state.update { it.copy(isUnsealing = true, lastResult = "", isError = false) }

            val sealedCredential = _state.value.sealedCredential
            val pin = _state.value.challengePin
            val challengeId = UUID.randomUUID().toString()

            appendLog("Unsealing with challenge: $challengeId")

            val result = ownerSpaceClient.unsealCredential(
                sealedCredential = sealedCredential,
                challengeId = challengeId,
                challengeResponse = pin
            )

            result.fold(
                onSuccess = { requestId ->
                    pendingRequests[requestId] = "credential.unseal"
                    appendLog("Sent unseal request: $requestId")
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isUnsealing = false,
                            lastResult = "Failed to send: ${error.message}",
                            isError = true
                        )
                    }
                    appendLog("Send failed: ${error.message}")
                }
            )
        }
    }

    private fun appendLog(message: String) {
        Log.d(TAG, message)
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        _state.update {
            val newLog = "[$timestamp] $message\n${it.debugLog}".take(2000)
            it.copy(debugLog = newLog)
        }
    }
}
