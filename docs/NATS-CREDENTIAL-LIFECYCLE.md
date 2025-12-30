# NATS Credential Lifecycle

This document describes the NATS credential lifecycle for the VettID Android app, including bootstrap, ongoing credentials, rotation, and auto-reconnection.

## Current State

### What Works
1. **Enrollment provides bootstrap credentials** (`VaultBootstrap` in `FinalizeResponse`):
   - NATS credential file (JWT + seed)
   - NATS endpoint URL
   - Optional CA certificate
   - Stored in `CredentialStore` via `storeNatsConnection()`

2. **Basic NATS infrastructure exists**:
   - `NatsConnectionManager` manages connection lifecycle
   - `NatsCredentials` data class with expiration tracking
   - `NatsTokenRefreshWorker` (periodic WorkManager task)
   - `OwnerSpaceClient` for vault pub/sub communication

### What's Missing
1. **No auto-connect on app startup** - NATS connection is not established automatically
2. **No ownerSpaceId in bootstrap** - Current `VaultBootstrap` lacks ownerSpaceId/messageSpaceId
3. **No vault-based credential refresh** - Current refresh uses REST API action tokens
4. **No credential rotation from vault** - Vault should push new credentials before expiration

---

## Backend Requirements

### 1. Enhanced Enrollment Response

The `/vault/enroll/finalize` response should include full connection info:

```json
{
  "status": "complete",
  "credential_package": { ... },
  "vault_status": "provisioned",
  "vault_bootstrap": {
    "credentials": "-----BEGIN NATS USER JWT-----\n...\n------END NATS USER JWT------\n\n-----BEGIN USER NKEY SEED-----\n...\n------END USER NKEY SEED------",
    "nats_endpoint": "tls://nats.vettid.dev:4222",
    "ca_certificate": "-----BEGIN CERTIFICATE-----\n...",
    "owner_space_id": "OwnerSpace.{user-guid}",
    "message_space_id": "MessageSpace.{user-guid}",
    "expires_at": "2025-01-01T00:00:00Z",
    "ttl_seconds": 86400
  }
}
```

**Required new fields:**
- `owner_space_id` - The owner's namespace for vault communication
- `message_space_id` - The message namespace for peer-to-peer communication
- `expires_at` - ISO 8601 timestamp when credentials expire
- `ttl_seconds` - Credential lifetime in seconds (for quick calculations)

### 2. Vault Credential Refresh Handler

The vault should implement a `credentials.refresh` handler that the app can request:

**Request** (app → vault):
```json
{
  "id": "request-uuid",
  "type": "credentials.refresh",
  "timestamp": "2025-12-30T10:00:00Z",
  "payload": {
    "current_credential_id": "cred-123",
    "device_id": "device-abc"
  }
}
```

**Response** (vault → app):
```json
{
  "event_id": "request-uuid",
  "success": true,
  "timestamp": "2025-12-30T10:00:01Z",
  "result": {
    "credentials": "-----BEGIN NATS USER JWT-----\n...",
    "expires_at": "2025-12-31T10:00:01Z",
    "ttl_seconds": 86400,
    "credential_id": "cred-456"
  }
}
```

### 3. Vault-Initiated Credential Push (Proactive Rotation)

The vault should proactively push new credentials before expiration:

**Topic:** `OwnerSpace.{guid}.forApp.credentials.rotate`

**Message:**
```json
{
  "type": "credentials.rotate",
  "timestamp": "2025-12-30T10:00:00Z",
  "payload": {
    "credentials": "-----BEGIN NATS USER JWT-----\n...",
    "expires_at": "2025-12-31T10:00:00Z",
    "ttl_seconds": 86400,
    "credential_id": "cred-789",
    "reason": "scheduled_rotation",
    "old_credential_id": "cred-456"
  }
}
```

### 4. Credential Status Check Handler

For app restart scenarios, the app needs to verify if cached credentials are still valid:

**Request:**
```json
{
  "id": "request-uuid",
  "type": "credentials.status",
  "payload": {
    "credential_id": "cred-123"
  }
}
```

**Response:**
```json
{
  "event_id": "request-uuid",
  "success": true,
  "result": {
    "valid": true,
    "expires_at": "2025-12-31T10:00:00Z",
    "remaining_seconds": 43200
  }
}
```

---

## App Implementation Plan

### Phase 1: Enhanced Credential Storage

Update `CredentialStore` to store complete credential info:

```kotlin
// Store full NATS connection with expiration
fun storeNatsConnection(connection: NatsConnectionInfo) {
    encryptedPrefs.edit().apply {
        putString(KEY_NATS_ENDPOINT, connection.endpoint)
        putString(KEY_NATS_CREDENTIALS, connection.credentials)
        putString(KEY_NATS_OWNER_SPACE, connection.ownerSpace)
        putString(KEY_NATS_MESSAGE_SPACE, connection.messageSpace)
        putLong(KEY_NATS_EXPIRES_AT, connection.expiresAtMillis)
        putString(KEY_NATS_CREDENTIAL_ID, connection.credentialId)
        connection.caCertificate?.let { putString(KEY_NATS_CA_CERT, it) }
        putLong(KEY_NATS_STORED_AT, System.currentTimeMillis())
    }.apply()
}

// Check if credentials need refresh (60 min buffer)
fun natsCredentialsNeedRefresh(): Boolean {
    val expiresAt = encryptedPrefs.getLong(KEY_NATS_EXPIRES_AT, 0)
    val bufferMs = 60 * 60 * 1000L // 1 hour
    return System.currentTimeMillis() + bufferMs > expiresAt
}
```

### Phase 2: Auto-Connect on App Startup

Add `NatsAutoConnector` triggered from `MainActivity`:

```kotlin
@Singleton
class NatsAutoConnector @Inject constructor(
    private val connectionManager: NatsConnectionManager,
    private val credentialStore: CredentialStore,
    private val ownerSpaceClient: OwnerSpaceClient
) {
    suspend fun autoConnectIfAvailable(): Result<Unit> {
        // Check if we have stored credentials
        if (!credentialStore.hasNatsConnection()) {
            return Result.failure(NatsException("No NATS credentials stored"))
        }

        // Check if credentials are expired
        if (credentialStore.natsCredentialsExpired()) {
            return Result.failure(NatsException("NATS credentials expired"))
        }

        // Build credentials from storage
        val credentials = buildCredentialsFromStorage() ?: return Result.failure(
            NatsException("Failed to parse stored credentials")
        )

        // Connect
        return connectionManager.connectWithStoredCredentials(credentials)
            .onSuccess {
                // Subscribe to vault topics
                ownerSpaceClient.subscribeToVault()

                // Check if refresh needed soon
                if (credentialStore.natsCredentialsNeedRefresh()) {
                    requestCredentialRefresh()
                }
            }
    }
}
```

### Phase 3: Credential Refresh Client

Add `NatsCredentialClient` for credential lifecycle:

```kotlin
@Singleton
class NatsCredentialClient @Inject constructor(
    private val ownerSpaceClient: OwnerSpaceClient,
    private val credentialStore: CredentialStore,
    private val connectionManager: NatsConnectionManager
) {
    /**
     * Request credential refresh from vault.
     */
    suspend fun requestRefresh(): Result<NatsCredentialRefreshResponse> {
        val payload = JsonObject().apply {
            addProperty("current_credential_id", credentialStore.getNatsCredentialId())
            addProperty("device_id", credentialStore.getDeviceId())
        }

        val requestId = ownerSpaceClient.sendToVault("credentials.refresh", payload)
            .getOrElse { return Result.failure(it) }

        return waitForResponse(requestId, timeout = 30.seconds)
    }

    /**
     * Handle vault-initiated credential rotation.
     */
    fun setupRotationListener() {
        viewModelScope.launch {
            ownerSpaceClient.vaultResponses
                .filterIsInstance<VaultResponse.HandlerResult>()
                .filter { it.handlerId == "credentials.rotate" }
                .collect { response ->
                    handleCredentialRotation(response)
                }
        }
    }

    private suspend fun handleCredentialRotation(response: VaultResponse.HandlerResult) {
        val result = response.result ?: return

        val newCredentials = NatsConnectionInfo(
            credentials = result.get("credentials").asString,
            ownerSpace = credentialStore.getNatsOwnerSpace()!!,
            messageSpace = credentialStore.getNatsMessageSpace()!!,
            endpoint = credentialStore.getNatsEndpoint()!!,
            expiresAt = parseIsoTimestamp(result.get("expires_at").asString),
            credentialId = result.get("credential_id").asString
        )

        // Store new credentials
        credentialStore.storeNatsConnection(newCredentials)

        // Reconnect with new credentials
        connectionManager.reconnectWithNewCredentials(newCredentials)
    }
}
```

### Phase 4: Background Credential Rotation

Update `NatsTokenRefreshWorker` to use vault-based refresh:

```kotlin
@HiltWorker
class NatsCredentialRefreshWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val connectionManager: NatsConnectionManager,
    private val credentialClient: NatsCredentialClient,
    private val credentialStore: CredentialStore
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Check if connected
        if (!connectionManager.isConnected()) {
            return Result.success() // Skip if not connected
        }

        // Check if refresh needed
        if (!credentialStore.natsCredentialsNeedRefresh()) {
            return Result.success()
        }

        // Request refresh from vault (NOT from REST API)
        return credentialClient.requestRefresh()
            .fold(
                onSuccess = { response ->
                    credentialStore.storeNatsConnection(response.toConnectionInfo())
                    connectionManager.reconnectWithNewCredentials(response)
                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Credential refresh failed: ${error.message}")
                    Result.retry()
                }
            )
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NatsCredentialRefreshWorker>(
                6, TimeUnit.HOURS,
                30, TimeUnit.MINUTES
            )
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "nats_credential_refresh",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )
        }
    }
}
```

### Phase 5: Connection Recovery

Handle connection drops and reconnection:

```kotlin
// In NatsConnectionManager
fun observeConnectionState() {
    scope.launch {
        connectionState.collect { state ->
            when (state) {
                is NatsConnectionState.Disconnected -> {
                    // Attempt reconnect after delay
                    delay(5.seconds)
                    autoReconnect()
                }
                is NatsConnectionState.Error -> {
                    // Check if credentials expired
                    if (credentialStore.natsCredentialsExpired()) {
                        // Need to re-authenticate (back to login)
                        emitEffect(NatsEffect.AuthenticationRequired)
                    } else {
                        // Retry with backoff
                        retryWithBackoff()
                    }
                }
                else -> {}
            }
        }
    }
}
```

---

## Sequence Diagrams

### Enrollment Flow
```
App                         Backend                      Vault
 |-- POST /enroll/finalize -->|                            |
 |                            |-- Provision vault -------->|
 |                            |<-- Vault ready ------------|
 |<-- FinalizeResponse -------|                            |
 |   (vault_bootstrap with                                 |
 |    owner_space_id,                                      |
 |    credentials, etc)                                    |
 |                                                         |
 |-- Store credentials locally                             |
 |-- Connect to NATS ---------------------------------->---|
 |-- Subscribe OwnerSpace.{guid}.forApp.> ------------->---|
 |<-- Connection established -------------------------------|
```

### App Restart Flow
```
App                              NATS                  Vault
 |-- Load stored credentials       |                      |
 |-- Check not expired             |                      |
 |-- Connect with credentials ---->|                      |
 |<-- Connected -------------------|                      |
 |-- Subscribe OwnerSpace.*.forApp.>                      |
 |                                                        |
 |   (if credentials need refresh)                        |
 |-- credentials.refresh ------------------------>------->|
 |<-- New credentials --------------------------------<---|
 |-- Store new credentials         |                      |
 |-- Reconnect with new creds ---->|                      |
```

### Vault-Initiated Rotation
```
Vault                            NATS                    App
 |   (credentials expire in 2hr)   |                      |
 |-- OwnerSpace.{guid}.forApp.credentials.rotate -------->|
 |                                 |<-- Receive message --|
 |                                 |   Parse new creds    |
 |                                 |   Store locally      |
 |<-- Disconnect old session ------|                      |
 |-- Connect new session ----------|--------------------->|
 |<-- Reconnected with new creds -------------------------|
```

---

## Migration Notes

### For Backend Developer

1. **Update `/vault/enroll/finalize` response** to include:
   - `owner_space_id`
   - `message_space_id`
   - `expires_at`
   - `ttl_seconds`

2. **Implement vault handlers**:
   - `credentials.refresh` - App-requested credential refresh
   - `credentials.status` - Check if credential is still valid

3. **Implement proactive rotation**:
   - Vault sends `credentials.rotate` message when credentials are within 1-2 hours of expiration
   - Old credentials remain valid for 5 minutes after new ones issued (graceful transition)

4. **Credential TTL recommendations**:
   - Bootstrap credentials: 24 hours
   - Ongoing credentials: 24 hours
   - Rotation trigger: 2 hours before expiration

### For Android Developer

1. **Update `VaultBootstrap` data class** to include new fields
2. **Add `NatsAutoConnector`** for app startup
3. **Add `NatsCredentialClient`** for refresh/rotation handling
4. **Update `NatsTokenRefreshWorker`** to use NATS instead of REST API
5. **Add connection state observer** for auto-reconnect
6. **Test edge cases**:
   - App killed while disconnected
   - Credentials expire while app in background
   - Network unavailable during rotation

---

## Expired Credential Recovery

### The Problem
If the user hasn't opened the app for an extended period (> 24 hours), their stored NATS credentials will be expired. The app cannot:
1. Connect to NATS with expired credentials
2. Request new credentials via NATS (can't connect)

### Solution: Vault Services Authentication

When NATS credentials are expired, the user must authenticate with Vault Services. The authentication flow:

1. App requests action from Vault Services API
2. Vault Services sends LAT challenge to app
3. App verifies LAT matches stored value (server authentication - prevents MITM)
4. App sends encrypted credential to Vault Services
5. Vault Services decrypts the credential
6. App prompts user for password
7. User enters password → app hashes (Argon2id) and encrypts with UTK
8. App sends encrypted password hash to Vault Services
9. Vault Services decrypts password hash with corresponding LTK
10. Vault Services compares hash to hash in credential
11. If match → Vault Services performs action and returns fresh NATS credentials

Expired NATS credentials are recovered as part of this normal login flow:

**Enhanced Auth Execute Response:**
```json
{
  "status": "authenticated",
  "action_token": "...",
  "expires_at": "...",
  "nats_credentials": {
    "credentials": "-----BEGIN NATS USER JWT-----\n...",
    "nats_endpoint": "tls://nats.vettid.dev:4222",
    "owner_space_id": "OwnerSpace.{guid}",
    "message_space_id": "MessageSpace.{guid}",
    "expires_at": "2025-12-31T10:00:00Z",
    "ttl_seconds": 86400
  }
}
```

**Flow:**
1. User opens app
2. App checks NATS credential expiration
3. If expired → start Vault Services auth flow
4. App calls `/action/request` → receives LAT challenge
5. App verifies LAT matches stored value
6. App sends encrypted credential
7. App prompts user for password (biometric optional for unlock)
8. User enters password → hashed (Argon2id) and encrypted with UTK
9. App calls `/auth/execute` with encrypted password hash
10. Vault Services decrypts with LTK, compares hash
11. If match → returns action token + fresh NATS credentials
12. App stores new NATS credentials and connects to NATS

```
Credential State          Action
─────────────────────────────────────────────────────
Not expired              → Connect normally via NATS
Expired                  → Require Vault Services login
                           (password + LAT verification)
                           → Returns fresh NATS credentials
No credentials stored    → Require enrollment
```

### App Implementation

```kotlin
@Singleton
class NatsCredentialManager @Inject constructor(
    private val credentialStore: CredentialStore,
    private val connectionManager: NatsConnectionManager
) {
    sealed class CredentialState {
        object Valid : CredentialState()
        object Expired : CredentialState()
        object NotEnrolled : CredentialState()
    }

    /**
     * Check NATS credential state on app startup.
     */
    fun checkCredentialState(): CredentialState {
        if (!credentialStore.hasNatsConnection()) {
            return CredentialState.NotEnrolled
        }

        val expiresAt = credentialStore.getNatsExpiresAt()
        val now = System.currentTimeMillis()

        return if (now < expiresAt) {
            CredentialState.Valid
        } else {
            CredentialState.Expired
        }
    }

    /**
     * Connect using stored (valid) credentials.
     */
    suspend fun connectWithStoredCredentials(): Result<Unit> {
        val credentials = credentialStore.buildNatsCredentials()
            ?: return Result.failure(NatsException("No credentials"))

        return connectionManager.connectWithCredentials(credentials)
    }
}
```

### Vault Services Login Handler

When the user logs into Vault Services:

```kotlin
// In AuthViewModel
suspend fun handleVaultServicesLogin(password: String): AuthResult {
    // Step 1: Request action token (receives LAT challenge)
    val actionRequest = vaultServiceClient.requestAction(actionType = "auth")

    // Step 2: Verify LAT matches stored value (server authentication)
    val receivedLat = actionRequest.latChallenge
    if (!credentialStore.verifyLat(receivedLat)) {
        return AuthResult.Error("Server authentication failed")
    }

    // Step 3: Hash password with Argon2id and encrypt with UTK
    val passwordHash = cryptoManager.hashPassword(password, credentialStore.getPasswordSalt())
    val encryptedHash = cryptoManager.encryptWithUtk(passwordHash)

    // Step 4: Execute auth with encrypted password hash
    val response = vaultServiceClient.executeAuth(
        encryptedPasswordHash = encryptedHash,
        actionToken = actionRequest.actionToken
    )

    return response.fold(
        onSuccess = { authResponse ->
            // Store action token
            credentialStore.storeActionToken(authResponse.actionToken)

            // Store fresh NATS credentials (included when expired/missing)
            authResponse.natsCredentials?.let { nats ->
                credentialStore.storeNatsConnection(nats.toConnectionInfo())

                // Connect to NATS
                natsConnectionManager.connectWithStoredCredentials()
            }

            AuthResult.Success
        },
        onFailure = { AuthResult.Error(it.message) }
    )
}
```

### Backend Requirements

**Modify `/auth/execute` response:**
1. Check if user's NATS credentials are expired or missing
2. If so, generate fresh NATS credentials
3. Include `nats_credentials` object in response

**Response includes NATS credentials when:**
- User has no active NATS credentials
- User's NATS credentials are expired
- User's NATS credentials expire within 1 hour (proactive refresh)

This piggybacks on the existing auth flow - no new endpoints needed.

---

## Security Considerations

1. **Credentials stored in EncryptedSharedPreferences** - Already implemented
2. **Credentials never logged** - Ensure no logging of JWT/seed values
3. **Clear credentials on logout** - Remove from storage when user logs out
4. **Validate credential source** - Only accept rotation messages from vault
5. **Graceful credential overlap** - Old credentials valid briefly after rotation
