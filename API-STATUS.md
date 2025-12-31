# VettID API Status

**Last Updated:** 2025-12-31 by Backend

This file is the master coordination point between backend development and mobile app development (iOS and Android). Mobile developers should reference this file to understand API availability and required actions.

---

## Endpoint Status

| Endpoint | Status | Notes |
|----------|--------|-------|
| POST /api/v1/enroll/start | **Deployed** | Start enrollment with invitation code |
| POST /api/v1/enroll/set-password | **Deployed** | Set password during enrollment |
| POST /api/v1/enroll/finalize | **Deployed** | Finalize enrollment, receive credential |
| POST /api/v1/action/request | **Deployed** | Request scoped action token |
| POST /api/v1/auth/execute | **Deployed** | Execute authentication with action token |
| GET /vault/health | **Deployed** | Get vault health status (running, stopped, etc) |
| GET /vault/status | **Deployed** | Get enrollment/credential status |
| POST /vault/start | **Deployed** | Start a stopped vault EC2 instance |
| POST /vault/stop | **Deployed** | Stop a running vault EC2 instance |
| POST /vault/terminate | **Deployed** | Terminate (delete) a vault instance |

---

## Backend Requests

### [REQUEST] Mobile-Accessible Vault Lifecycle Endpoints

**Priority:** High
**Status:** Pending Backend Implementation

**Problem:**
- Vault lifecycle endpoints (`/vault/start`, `/vault/stop`, `/vault/health`) require Cognito member JWT
- Mobile apps authenticate via vault services (enrollment JWT) and NATS, not Cognito
- After enrollment, vault EC2 may be stopped - mobile cannot start it without Cognito auth

**Proposed Solutions:**

1. **Action Token Approach** (Preferred)
   - Add `vault_start`, `vault_stop`, `vault_status` action types
   - Mobile uses existing `/api/v1/action/request` flow
   - Consistent with existing mobile auth pattern

2. **Enrollment Token Approach**
   - Allow enrollment JWT to access vault lifecycle endpoints
   - Scope limited to the enrolled user's vault only

3. **NATS-Based Approach**
   - Add vault lifecycle handlers (`vault.start`, `vault.stop`, `vault.status`)
   - Mobile calls via OwnerSpace NATS handlers
   - Requires vault-services to listen on control topic

**Mobile Impact:**
- Currently shows "Connection failed" when vault is stopped
- Cannot recover without backend-side intervention
- Blocks testing of NATS messaging features

---

### [ANSWERED] app.bootstrap Handler - Full NATS Credentials Exchange

**Priority:** High
**Status:** ✅ Implemented and Deployed

**Answer from Backend (2025-12-30):**
Yes, the `app.bootstrap` handler is fully implemented in `vault-manager/internal/handlers/builtins/bootstrap.go` and deployed.

#### Request Format
```json
{
  "id": "unique-request-id",
  "type": "app.bootstrap",
  "timestamp": "2025-12-30T15:30:00Z",
  "payload": {
    "device_id": "android-device-xyz",
    "device_type": "android",
    "app_version": "1.0.0",
    "requested_ttl_hours": 168,
    "app_session_public_key": "base64-encoded-X25519-public-key"
  }
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `device_id` | string | Yes | Unique device identifier |
| `device_type` | string | No | "android" or "ios" |
| `app_version` | string | No | App version for compatibility |
| `requested_ttl_hours` | int | No | Credential TTL (default: 168 = 7 days, max: 720 = 30 days) |
| `app_session_public_key` | string | No | X25519 public key (base64) for E2E encryption |

#### Response Format
```json
{
  "event_id": "unique-request-id",
  "success": true,
  "timestamp": "2025-12-30T15:30:01Z",
  "result": {
    "credentials": "-----BEGIN NATS USER JWT-----\n...",
    "nats_endpoint": "tls://nats.vettid.dev:4222",
    "owner_space": "OwnerSpace.abc123",
    "message_space": "MessageSpace.abc123",
    "topics": {
      "send_to_vault": "OwnerSpace.abc123.forVault.>",
      "receive_from_vault": "OwnerSpace.abc123.forApp.>"
    },
    "expires_at": "2026-01-06T15:30:00Z",
    "ttl_seconds": 604800,
    "credential_id": "cred-12345678",
    "rotation_info": {
      "rotate_before_hours": 24,
      "rotation_topic": "OwnerSpace.abc123.forVault.credentials.refresh"
    },
    "session_info": {
      "session_id": "sess-87654321",
      "vault_session_public_key": "base64-encoded-vault-X25519-public-key",
      "session_expires_at": "2026-01-06T15:30:00Z",
      "encryption_enabled": true
    }
  }
}
```

**Notes:**
- `session_info` is only present if `app_session_public_key` was provided in the request
- `credentials` is a full NATS credentials file (JWT + seed) - replace bootstrap creds with this
- Topic structure: `OwnerSpace.{guid}.forVault.app.bootstrap` → response on `OwnerSpace.{guid}.forApp.>`

**Mobile Implementation Status (Android):**
- [x] Implemented `BootstrapClient` class for `app.bootstrap` credential exchange
- [x] Updated `NatsAutoConnector` to detect bootstrap credentials and trigger exchange
- [x] Full credentials stored via `CredentialStore.storeFullNatsCredentials()`
- [x] Automatic reconnection after receiving full credentials
- [x] Session key exchange integrated with E2E encryption setup
- [x] Updated `BootstrapClient` response parsing to match backend format (credentials field in result)

**Testing Status (2025-12-31):**
- [x] App connects to NATS with bootstrap credentials
- [x] App subscribes to `OwnerSpace.{guid}.forApp.bootstrap.>` for responses
- [x] App publishes bootstrap request to `OwnerSpace.{guid}.forVault.app.bootstrap`
- [ ] **BLOCKED**: Vault-manager not responding to bootstrap requests (30s timeout)

**Backend Investigation Needed:**
CloudWatch/journald logs from vault-manager instance show the bootstrap request IS being received and processed:
```
Dec 31 15:32:01 vault-manager: {"level":"info","session_id":"sess_e8311f605e7e217c10106406655e46da","device_id":"user-...","message":"Created new E2E session"}
Dec 31 15:32:01 vault-manager: {"level":"info","session_id":"sess_e8311f605e7e217c10106406655e46da","message":"E2E encryption session established"}
```

But the response never reaches the app (times out 30 seconds later).

**Root Cause Identified:**
- App sends request at 15:31:58 UTC to `forVault.app.bootstrap`
- Vault-manager processes request at 15:32:01 UTC (3 seconds later)
- Vault-manager creates E2E session successfully
- **BUT** response is not being published to the topic the app is subscribed to
- App subscribes to `forApp.bootstrap.>` (as specified in enrollment `response_topic`)
- App times out at 15:32:28 UTC

**Backend Action Required:**
The vault-manager bootstrap handler needs to publish responses to `OwnerSpace.{guid}.forApp.bootstrap.{requestId}` to match the `response_topic` specified in the enrollment response. Currently it appears the response is either:
1. Not being published at all, or
2. Being published to a different topic (e.g., `forApp.>` without the `bootstrap.` prefix)

---

## Recent Changes

### 2025-12-31 - Lambda Connection/Messaging Handlers Removed

- **Endpoints REMOVED:** All HTTP API endpoints for connections and messaging
  - `/member/connections/*` - **REMOVED**
  - `/member/messages/*` - **REMOVED**
  - `/connections/*` - **REMOVED**
  - `/messages/*` - **REMOVED**

- **Breaking:** Yes - Mobile must use NATS vault handlers instead

- **Architecture Change:** Connections and messaging now handled vault-to-vault via NATS
  - All connection operations go through `connection.*` NATS handlers
  - All messaging operations go through `message.*` NATS handlers
  - X25519 keys and peer credentials stored in vault JetStream KV (not DynamoDB)

- **Mobile Action Required:**
  - [x] Android: Update ConnectionsViewModel to use NATS ConnectionsClient instead of HTTP API
  - [x] Android: Remove unused ConnectionApiClient and MessagingApiClient

### 2025-12-31 - Vault Lifecycle Endpoints Deployed

- **Endpoints:** Complete vault lifecycle management now available
  - `GET /vault/health` - Check vault health/status (running, stopped, terminated, etc)
  - `GET /vault/status` - Check enrollment and credential status
  - `POST /vault/start` - Start a stopped vault EC2 instance
  - `POST /vault/stop` - Stop a running vault EC2 instance
  - `POST /vault/terminate` - Terminate and delete a vault instance

- **Breaking:** No - New functionality only

- **Authentication:** All endpoints currently require member JWT authorization
  - **ISSUE:** Mobile apps don't use Cognito - need action token or NATS-based approach
  - **Workaround pending:** Backend needs to add `vault_start`/`vault_stop` action types

- **Usage:**
  ```kotlin
  // Start a stopped vault
  POST /vault/start
  Authorization: Bearer {member_jwt}

  Response:
  {
    "status": "starting",
    "instance_id": "i-xxx",
    "message": "Vault is starting. Please wait for initialization to complete."
  }

  // Stop a running vault
  POST /vault/stop
  Authorization: Bearer {member_jwt}

  Response:
  {
    "status": "stopping",
    "instance_id": "i-xxx",
    "message": "Vault is being stopped."
  }
  ```

- **Mobile Action Required:**
  - [ ] Android: Add vault lifecycle controls to settings/vault management screen (blocked on auth)
  - [ ] Android: Show vault status indicator (running/stopped/starting)

### 2025-12-30 - Vault-to-Vault Real-Time Messaging Deployed

- **Endpoints:** New NATS event handlers for peer-to-peer messaging
  - `message.send` - Send encrypted message to peer vault
  - `message.read-receipt` - Send read receipt to sender vault
  - `profile.broadcast` - Broadcast profile updates to all connections
  - `connection.notify-revoke` - Notify peer of connection revocation

- **Breaking:** No - New functionality only

- **Architecture:** Messages flow vault-to-vault via NATS MessageSpace, not through Lambda
  - App → Vault (OwnerSpace.forVault) → Peer Vault (MessageSpace.forOwner) → Peer App (OwnerSpace.forApp)
  - Lambda handlers store messages in DynamoDB for backup/history only

- **Incoming Message Types on MessageSpace.forOwner:**
  - `message` - Incoming message from peer
  - `read-receipt` - Read receipt from peer
  - `profile-update` - Profile update from peer
  - `revoked` - Connection revocation notice from peer

- **App Notifications on OwnerSpace.forApp:**
  - `forApp.new-message` - New message received from peer
  - `forApp.read-receipt` - Read receipt received from peer
  - `forApp.profile-update` - Profile update from peer
  - `forApp.connection-revoked` - Connection revoked by peer

- **Mobile Action Required:**
  - [x] Android: Implement `message.send` for sending messages - `NatsMessagingClient.sendMessage()`
  - [x] Android: Subscribe to `forApp.new-message` for incoming messages - `OwnerSpaceClient.incomingMessages` flow
  - [x] Android: Handle `forApp.read-receipt` notifications - `OwnerSpaceClient.readReceipts` flow
  - [x] Android: Handle `forApp.connection-revoked` notifications - `OwnerSpaceClient.connectionRevocations` flow

- **Notes:**
  - See "Messaging (Peer-to-Peer)" section below for full API documentation
  - Credentials for peer connections are stored via `connection.store-credentials`

### 2025-12-30 - Credential Lifecycle Handlers Deployed

Implemented `credentials.refresh` and `credentials.status` handlers as requested in `docs/NATS-CREDENTIAL-LIFECYCLE.md`.

| Feature | Status | Notes |
|---------|--------|-------|
| Enhanced enrollment with owner_space_id | **DONE** | `app.bootstrap` response includes `owner_space`, `message_space`, `credential_id`, `ttl_seconds` |
| `credentials.refresh` handler | **DONE** | Returns new credentials with `credential_id` for tracking |
| `credentials.status` handler | **DONE** | Returns `valid`, `expires_at`, `remaining_seconds` |
| Vault-initiated credential push | **DONE** | Proactive rotation 2 hours before expiry via `forApp.credentials.rotate` |

#### credentials.refresh

**Request** (app → vault via `OwnerSpace.{guid}.forVault.credentials.refresh`):
```json
{
  "current_credential_id": "cred-abc12345",
  "device_id": "device-xyz"
}
```

**Response:**
```json
{
  "credentials": "-----BEGIN NATS USER JWT-----\n...",
  "expires_at": "2026-01-06T12:00:00Z",
  "ttl_seconds": 604800,
  "credential_id": "cred-def67890"
}
```

#### credentials.status

**Request** (app → vault via `OwnerSpace.{guid}.forVault.credentials.status`):
```json
{
  "credential_id": "cred-abc12345"
}
```

**Response:**
```json
{
  "valid": true,
  "expires_at": "2026-01-06T12:00:00Z",
  "remaining_seconds": 518400
}
```

#### Vault-Initiated Credential Rotation

The vault proactively pushes new credentials 2 hours before expiry. Check interval: 15 minutes.

**Topic:** `OwnerSpace.{guid}.forApp.credentials.rotate`

**Message:**
```json
{
  "type": "credentials.rotate",
  "timestamp": "2025-12-30T10:00:00Z",
  "payload": {
    "credentials": "-----BEGIN NATS USER JWT-----\n...",
    "expires_at": "2026-01-06T12:00:00Z",
    "ttl_seconds": 604800,
    "credential_id": "cred-new12345",
    "reason": "scheduled_rotation",
    "old_credential_id": "cred-old67890"
  }
}
```

**Reason values:**
- `scheduled_rotation` - Normal rotation (2+ hours remaining)
- `expiry_imminent` - Urgent rotation (<30 minutes remaining)

- **Mobile Action Required:**
  - [x] Android: Implement `NatsCredentialClient.requestRefresh()` using `credentials.refresh` ✅
  - [x] Android: Update `NatsAutoConnector` to check credentials on startup ✅
  - [x] Android: Update `NatsTokenRefreshWorker` to use vault-based refresh ✅
  - [x] Android: Subscribe to `forApp.credentials.rotate` for proactive rotation ✅
  - [x] Android: Handle rotation message: store new credentials, reconnect with new creds ✅

### 2025-12-30 - Backend Response to Credential Lifecycle Document (Superseded)

~See updated section above. The requested handlers are now implemented.~

### 2025-11-27 - Vault Services Infrastructure Deployed

- **Endpoints:** All enrollment and authentication endpoints now deployed
  - POST /api/v1/enroll/start
  - POST /api/v1/enroll/set-password
  - POST /api/v1/enroll/finalize
  - POST /api/v1/action/request
  - POST /api/v1/auth/execute

- **Breaking:** Yes - API design changed from original spec
  - Old: Single POST /api/v1/enroll endpoint
  - New: Multi-step enrollment (start → set-password → finalize)
  - Old: 3-step auth (request-lat → challenge → verify)
  - New: 2-step auth (action/request → auth/execute)

- **Mobile Action Required:**
  - [ ] iOS: Implement new multi-step enrollment flow
  - [ ] iOS: Implement action-specific authentication flow
  - [ ] iOS: Update crypto to handle UTK encryption
  - [x] Android: Implement new multi-step enrollment flow - `ApiClient` + `EnrollmentViewModel`
  - [x] Android: Implement action-specific authentication flow - `ApiClient` + `AuthenticationViewModel`
  - [x] Android: Update crypto to handle UTK encryption - `CryptoManager.encryptPasswordForServer()`

- **Notes:**
  - Key ownership changed: **Ledger now owns all keys (CEK, LTK)**
  - Mobile only stores: encrypted blob, UTKs (public keys), LAT
  - UTKs are used to encrypt password hashes before sending to server
  - LAT is used for mutual authentication (phishing protection)

### 2025-11-26 - Test Harness Infrastructure Ready

- **What:** Test harness project structure created
- **Breaking:** N/A
- **Notes:** Test harness uses `@noble/curves` for cryptography

---

## Mobile Status

### iOS
| Feature | Status | Notes |
|---------|--------|-------|
| Project Setup | Complete | Basic Xcode project created |
| Enrollment | **Action Required** | Backend API ready, implement multi-step flow |
| Auth | **Action Required** | Backend API ready, implement action tokens |
| Vault | Not Started | Awaiting backend API |

### Android
| Feature | Status | Notes |
|---------|--------|-------|
| Project Setup | Complete | Basic Android Studio project created |
| Enrollment | **Action Required** | Backend API ready, implement multi-step flow |
| Auth | **Action Required** | Backend API ready, implement action tokens |
| Vault | Not Started | Awaiting backend API |

---

## API Specifications (Updated)

### Key Ownership Model (Important Change!)

**Ledger (Backend) Owns:**
- CEK (Credential Encryption Key) - X25519 private key for encrypting credential blob
- LTK (Ledger Transaction Key) - X25519 private key for decrypting password hashes

**Mobile Stores:**
- Encrypted credential blob (cannot decrypt without CEK)
- UTK pool (User Transaction Keys) - X25519 **public** keys for encrypting password
- LAT (Ledger Auth Token) - 256-bit token for verifying server authenticity

### Enrollment Flow (Multi-Step)

**Step 1: Start Enrollment**
```
POST /api/v1/enroll/start
{
  "invitation_code": "string",
  "device_id": "string (vendor ID)",
  "attestation_data": "base64 (platform attestation)"
}
→ {
    "enrollment_session_id": "enroll_xxx",
    "user_guid": "user_xxx",
    "transaction_keys": [
      { "key_id": "tk_xxx", "public_key": "base64", "algorithm": "X25519" },
      ... // 20 keys
    ],
    "password_prompt": {
      "use_key_id": "tk_xxx",
      "message": "Please create a secure password..."
    }
  }
```

**Step 2: Set Password**
```
POST /api/v1/enroll/set-password
{
  "enrollment_session_id": "enroll_xxx",
  "encrypted_password_hash": "base64",  // Argon2id hash encrypted with UTK
  "key_id": "tk_xxx",                   // Must match password_prompt.use_key_id
  "nonce": "base64"                     // 96-bit random nonce
}
→ {
    "status": "password_set",
    "next_step": "finalize"
  }
```

**Step 3: Finalize Enrollment**
```
POST /api/v1/enroll/finalize
{
  "enrollment_session_id": "enroll_xxx"
}
→ {
    "status": "enrolled",
    "credential_package": {
      "user_guid": "user_xxx",
      "encrypted_blob": "base64",        // Store this - cannot decrypt
      "cek_version": 1,
      "ledger_auth_token": {
        "lat_id": "lat_xxx",
        "token": "hex",                  // Store securely for verification
        "version": 1
      },
      "transaction_keys": [...]          // Remaining unused UTKs
    },
    "vault_status": "PROVISIONING"
  }
```

### Authentication Flow (Action-Specific)

**Step 1: Request Action Token**
```
POST /api/v1/action/request
Authorization: Bearer {cognito_token}
{
  "user_guid": "user_xxx",
  "action_type": "authenticate",
  "device_fingerprint": "optional"
}
→ {
    "action_token": "eyJ...",            // JWT scoped to specific endpoint
    "action_token_expires_at": "ISO8601",
    "ledger_auth_token": {
      "lat_id": "lat_xxx",
      "token": "hex",                    // Compare with stored LAT!
      "version": 1
    },
    "action_endpoint": "/api/v1/auth/execute",
    "use_key_id": "tk_xxx"               // UTK to use for password encryption
  }
```

**IMPORTANT:** Mobile MUST verify `ledger_auth_token.token` matches stored LAT before proceeding!

**Step 2: Execute Authentication**
```
POST /api/v1/auth/execute
Authorization: Bearer {action_token}     // NOT Cognito token!
{
  "encrypted_blob": "base64",            // Current encrypted blob
  "cek_version": 1,
  "encrypted_password_hash": "base64",   // Argon2id hash encrypted with UTK
  "ephemeral_public_key": "base64",      // Your X25519 ephemeral public key
  "nonce": "base64",
  "key_id": "tk_xxx"                     // Must match use_key_id from step 1
}
→ {
    "status": "success",
    "action_result": {
      "authenticated": true,
      "message": "Authentication successful",
      "timestamp": "ISO8601"
    },
    "credential_package": {
      "encrypted_blob": "base64",        // NEW blob - replace stored blob
      "cek_version": 2,                  // Incremented - CEK rotated
      "ledger_auth_token": {
        "lat_id": "lat_xxx",
        "token": "hex",                  // NEW LAT - replace stored LAT
        "version": 2                     // Incremented - LAT rotated
      },
      "new_transaction_keys": [...]      // Replenished if pool low
    },
    "used_key_id": "tk_xxx"              // Remove this UTK from pool
  }
```

### Action Types

| Action Type | Endpoint | Description |
|-------------|----------|-------------|
| `authenticate` | /api/v1/auth/execute | Basic authentication |
| `add_secret` | /api/v1/secrets/add | Add a secret to vault |
| `retrieve_secret` | /api/v1/secrets/retrieve | Retrieve a secret |
| `add_policy` | /api/v1/policies/update | Update vault policies |
| `modify_credential` | /api/v1/credential/modify | Modify credential |

### Error Codes

| Code | Meaning |
|------|---------|
| 400 | Bad Request - missing or invalid parameters |
| 401 | Unauthorized - invalid or missing token |
| 403 | Forbidden - token already used or wrong scope |
| 404 | Not Found - resource doesn't exist |
| 409 | Conflict - version mismatch or state conflict |
| 410 | Gone - invitation expired |
| 500 | Internal Server Error |

---

## Cryptographic Requirements

### Password Encryption Flow

1. User enters password
2. Hash with Argon2id: `password_hash = Argon2id(password, salt)`
3. Generate ephemeral X25519 keypair
4. Compute shared secret: `shared = X25519(ephemeral_private, UTK_public)`
5. Derive encryption key: `key = HKDF-SHA256(shared, "password-encryption")`
6. Encrypt: `encrypted = ChaCha20-Poly1305(password_hash, key, nonce)`
7. Send: `encrypted_password_hash`, `ephemeral_public_key`, `nonce`, `key_id`

### Key Types

| Key Type | Algorithm | Location | Purpose |
|----------|-----------|----------|---------|
| CEK | X25519 | Ledger only | Encrypt credential blob |
| LTK | X25519 | Ledger only | Decrypt password hashes |
| UTK | X25519 | Mobile (public only) | Encrypt password hashes |
| LAT | 256-bit random | Both | Mutual authentication |

### Platform-Specific Implementations

**iOS:**
- Use CryptoKit for X25519 (`Curve25519.KeyAgreement`)
- Use swift-crypto for ChaCha20-Poly1305
- Store UTKs and LAT in Keychain with `.whenUnlockedThisDeviceOnly`
- Use App Attest for device attestation
- Use Argon2 via external library (argon2-swift or similar)

**Android:**
- Use Tink or BouncyCastle for X25519
- Use Tink for ChaCha20-Poly1305
- Store UTKs and LAT in Android Keystore
- Use Hardware Key Attestation (GrapheneOS compatible)
- Use argon2-jvm for Argon2id

---

## NATS Communication (Vault Operations)

**Important:** All secrets and profile operations go through NATS to the vault EC2 instance. These endpoints are **only accessible when the vault is online**.

### NATS Topics

| Topic | Direction | Purpose |
|-------|-----------|---------|
| `OwnerSpace.{user_guid}.forVault.>` | App → Vault | Send commands to vault |
| `OwnerSpace.{user_guid}.forApp.>` | Vault → App | Receive responses from vault |
| `OwnerSpace.{user_guid}.control.>` | System | System control messages |

### Message Format (App → Vault)

**IMPORTANT:** The vault-manager expects this exact JSON structure:

```json
{
  "id": "unique-request-id",
  "type": "secrets.datastore.add",
  "timestamp": "2025-12-22T15:30:00Z",
  "payload": { ... },
  "reply_to": "optional-reply-subject"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `id` | string | Yes | Unique request ID for correlation (UUID recommended) |
| `type` | string | Yes | Handler/event type (see Event Types below) |
| `timestamp` | string | Yes | ISO 8601 timestamp |
| `payload` | object | Yes | Handler-specific payload |
| `reply_to` | string | No | Optional custom reply subject |

**⚠️ Common Mistakes:**
- Using `requestId` instead of `id`
- Using epoch milliseconds instead of ISO 8601 string
- Prefixing type with `events.` (e.g., `events.profile.update` instead of `profile.update`)

### Response Format (Vault → App)

```json
{
  "event_id": "the-request-id-from-message",
  "success": true,
  "timestamp": "2025-12-22T15:30:01Z",
  "result": { ... },
  "error": null
}
```

| Field | Type | Description |
|-------|------|-------------|
| `event_id` | string | Matches the `id` from the request |
| `success` | boolean | Whether the operation succeeded |
| `timestamp` | string | When the response was generated |
| `result` | object | Handler result (if success=true) |
| `error` | string | Error message (if success=false) |

### Event Types (Built-in Handlers)

#### Secrets - Datastore (Minor Secrets)

These are stored in local JetStream KV on the vault EC2.

**`secrets.datastore.add`** - Add a new secret
```json
{
  "key": "github_pat",
  "value": "encrypted_base64...",
  "metadata": {
    "label": "GitHub Personal Access Token",
    "category": "api_key",
    "tags": ["github", "development"]
  }
}
→ { "success": true, "key": "github_pat" }
```

**`secrets.datastore.update`** - Update existing secret
```json
{
  "key": "github_pat",
  "value": "new_encrypted_base64...",
  "metadata": { "label": "Updated GitHub PAT" }
}
→ { "success": true, "key": "github_pat" }
```

**`secrets.datastore.retrieve`** - Get a secret by key
```json
{
  "key": "github_pat"
}
→ {
    "key": "github_pat",
    "value": "encrypted_base64...",
    "metadata": { "label": "...", "category": "...", "tags": [...] }
  }
```

**`secrets.datastore.delete`** - Delete a secret
```json
{
  "key": "github_pat"
}
→ { "success": true, "key": "github_pat" }
```

**`secrets.datastore.list`** - List secrets (metadata only)
```json
{
  "category": "password",
  "tag": "work",
  "limit": 50,
  "cursor": "optional_pagination_cursor"
}
→ {
    "items": [
      { "key": "gmail", "metadata": {...}, "created_at": "..." },
      ...
    ],
    "next_cursor": "..."
  }
```

#### Profile

Profile fields are encrypted values stored in the vault.

**`profile.get`** - Get profile fields
```json
{
  "fields": ["email", "display_name"]
}
→ {
    "fields": {
      "email": { "value": "encrypted...", "updated_at": "..." },
      "display_name": { "value": "encrypted...", "updated_at": "..." }
    }
  }
```
*Note: Empty `fields` array returns all fields.*

**`profile.update`** - Update profile fields
```json
{
  "fields": {
    "display_name": "encrypted_new_value...",
    "bio": "encrypted_bio..."
  }
}
→ { "success": true, "fields_updated": 2 }
```

**`profile.delete`** - Delete profile fields
```json
{
  "fields": ["bio", "phone"]
}
→ { "success": true, "fields_deleted": 2 }
```

#### Credential

Credential blob management (CEK-encrypted, synced from mobile).

**`credential.store`** - Store initial credential (enrollment)
```json
{
  "encrypted_blob": "base64...",
  "ephemeral_public_key": "base64...",
  "nonce": "base64...",
  "version": 1
}
→ { "success": true, "version": 1 }
```

**`credential.sync`** - Sync credential after auth rotation
```json
{
  "encrypted_blob": "base64...",
  "ephemeral_public_key": "base64...",
  "nonce": "base64...",
  "version": 2
}
→ { "success": true, "version": 2 }
```

**`credential.get`** - Get current credential
```json
{}
→ {
    "encrypted_blob": "base64...",
    "ephemeral_public_key": "base64...",
    "nonce": "base64...",
    "version": 2,
    "synced_at": "2025-12-22T15:30:00Z"
  }
```

**`credential.version`** - Check credential version
```json
{}
→ { "version": 2, "exists": true }
```

#### Connections (Peer-to-Peer Messaging)

Connection handlers enable secure peer-to-peer messaging between vaults.
Each connection creates scoped NATS credentials allowing peers to communicate.

**`connection.create-invite`** - Create invitation for a peer
```json
{
  "peer_guid": "user-ABC123...",
  "label": "Alice's Vault",
  "expires_in_hours": 24
}
→ {
    "connection_id": "conn-xyz...",
    "nats_credentials": "-----BEGIN NATS USER JWT-----\n...",
    "owner_space_id": "OwnerSpace.abc123",
    "message_space_id": "MessageSpace.abc123",
    "expires_at": "2025-12-23T15:30:00Z"
  }
```

**`connection.store-credentials`** - Store credentials from a peer's invitation
```json
{
  "connection_id": "conn-xyz...",
  "peer_guid": "user-DEF456...",
  "label": "Bob's Vault",
  "nats_credentials": "-----BEGIN NATS USER JWT-----\n...",
  "peer_owner_space_id": "OwnerSpace.def456",
  "peer_message_space_id": "MessageSpace.def456"
}
→ {
    "connection_id": "conn-xyz...",
    "peer_guid": "user-DEF456...",
    "label": "Bob's Vault",
    "status": "active",
    "direction": "inbound",
    "created_at": "2025-12-22T15:30:00Z"
  }
```

**`connection.rotate`** - Rotate credentials for a connection
```json
{
  "connection_id": "conn-xyz..."
}
→ {
    "connection_id": "conn-xyz...",
    "status": "active",
    "last_rotated_at": "2025-12-22T16:00:00Z",
    "nats_credentials": "-----BEGIN NATS USER JWT-----\n..."
  }
```

**`connection.revoke`** - Revoke a connection
```json
{
  "connection_id": "conn-xyz..."
}
→ { "success": true, "connection_id": "conn-xyz..." }
```

**`connection.list`** - List all connections
```json
{
  "status": "active",
  "limit": 50,
  "cursor": "optional_pagination_cursor"
}
→ {
    "items": [
      {
        "connection_id": "conn-xyz...",
        "peer_guid": "user-DEF456...",
        "label": "Bob's Vault",
        "status": "active",
        "direction": "inbound",
        "created_at": "2025-12-22T15:30:00Z",
        "expires_at": "2025-12-23T15:30:00Z"
      }
    ],
    "next_cursor": "..."
  }
```

**`connection.get-credentials`** - Get credentials for a connection
```json
{
  "connection_id": "conn-xyz..."
}
→ {
    "nats_credentials": "-----BEGIN NATS USER JWT-----\n...",
    "peer_message_space_id": "MessageSpace.def456",
    "expires_at": "2025-12-23T15:30:00Z"
  }
```

#### Messaging (Peer-to-Peer)

Messages flow vault-to-vault via NATS, not through Lambda. Architecture:
- App → Vault (OwnerSpace.forVault) → Peer Vault (MessageSpace.forOwner) → Peer App (OwnerSpace.forApp)

**`message.send`** - Send encrypted message to peer vault
```json
{
  "connection_id": "conn-xyz...",
  "encrypted_content": "base64_encrypted_message...",
  "nonce": "base64_nonce...",
  "content_type": "text"
}
→ {
    "message_id": "msg-abc123...",
    "connection_id": "conn-xyz...",
    "sent_at": "2025-12-30T15:30:00Z",
    "status": "sent"
  }
```

**`message.read-receipt`** - Send read receipt to peer vault
```json
{
  "connection_id": "conn-xyz...",
  "message_id": "msg-abc123..."
}
→ {
    "message_id": "msg-abc123...",
    "read_at": "2025-12-30T15:31:00Z",
    "sent": true
  }
```

**`profile.broadcast`** - Broadcast profile updates to all connections
```json
{
  "fields": ["display_name", "avatar_url"]
}
→ {
    "success": true,
    "connections_count": 5,
    "success_count": 5,
    "failed_connection_ids": [],
    "broadcast_at": "2025-12-30T15:30:00Z"
  }
```
*Note: Empty `fields` array broadcasts all profile fields.*

**`connection.notify-revoke`** - Notify peer before revoking connection
```json
{
  "connection_id": "conn-xyz..."
}
→ {
    "success": true,
    "notified_at": "2025-12-30T15:30:00Z"
  }
```
*Note: This also revokes the connection after notifying the peer.*

#### Incoming Notifications (Vault → App)

These notifications arrive on `OwnerSpace.{guid}.forApp.{type}`:

**`forApp.new-message`** - New message from peer
```json
{
  "type": "new-message",
  "timestamp": "2025-12-30T15:30:00Z",
  "data": {
    "message_id": "msg-abc123...",
    "sender_guid": "user-DEF456...",
    "connection_id": "conn-xyz...",
    "encrypted_content": "base64_encrypted...",
    "nonce": "base64_nonce...",
    "content_type": "text",
    "sent_at": "2025-12-30T15:30:00Z"
  }
}
```

**`forApp.read-receipt`** - Peer read your message
```json
{
  "type": "read-receipt",
  "timestamp": "2025-12-30T15:31:00Z",
  "data": {
    "message_id": "msg-abc123...",
    "connection_id": "conn-xyz...",
    "read_at": "2025-12-30T15:31:00Z"
  }
}
```

**`forApp.profile-update`** - Profile update from peer
```json
{
  "type": "profile-update",
  "timestamp": "2025-12-30T15:30:00Z",
  "data": {
    "connection_id": "conn-xyz...",
    "fields": {
      "display_name": "Alice",
      "avatar_url": "https://..."
    },
    "updated_at": "2025-12-30T15:30:00Z"
  }
}
```

**`forApp.connection-revoked`** - Peer revoked connection
```json
{
  "type": "connection-revoked",
  "timestamp": "2025-12-30T15:30:00Z",
  "data": {
    "connection_id": "conn-xyz...",
    "revoked_at": "2025-12-30T15:30:00Z",
    "reason": "user_initiated"
  }
}
```

### Android Implementation Notes

**VaultMessage format (FIXED in OwnerSpaceClient.kt):**
```kotlin
// Correct format - matches vault-manager expectations
data class VaultMessage(
    val id: String,           // Unique request ID for correlation
    val type: String,         // Handler type (e.g., "profile.get")
    val payload: JsonObject,
    val timestamp: String     // ISO 8601 format
) {
    companion object {
        fun create(type: String, payload: JsonObject): VaultMessage {
            return VaultMessage(
                id = UUID.randomUUID().toString(),
                type = type,
                payload = payload,
                timestamp = Instant.now().toString()
            )
        }
    }
}
```

**Response parsing (FIXED):**
- Vault-manager returns `event_id` (not `requestId`)
- `VaultResponseJson` now handles both for backwards compatibility
- Timestamps are ISO 8601 strings, parsed to epoch when needed

**ConnectionsClient (NEW):**
```kotlin
// Type-safe client for peer-to-peer connections
val connectionsClient = ConnectionsClient(ownerSpaceClient)

// Create invitation for a peer
val invite = connectionsClient.createInvite(
    peerGuid = "user-ABC123",
    label = "Alice's Vault"
).getOrThrow()

// Store credentials from peer invitation
val connection = connectionsClient.storeCredentials(
    connectionId = invite.connectionId,
    peerGuid = "user-DEF456",
    label = "Bob's Vault",
    natsCredentials = receivedCredentials,
    peerOwnerSpaceId = "OwnerSpace.def456",
    peerMessageSpaceId = "MessageSpace.def456"
).getOrThrow()

// List active connections
val connections = connectionsClient.list(status = "active").getOrThrow()
```

### Testing NATS Communication

1. **Ensure vault is online** - Check `/member/vault/status` returns `running`
2. **Get NATS credentials** - Call `/vault/nats/token` to get fresh credentials
3. **Connect to NATS** - Use credentials to connect to `nats.vettid.dev:4222`
4. **Subscribe to responses** - `OwnerSpace.{user_guid}.forApp.>`
5. **Send test event** - Publish to `OwnerSpace.{user_guid}.forVault.profile.get`
6. **Verify response** - Check `event_id` matches your request `id`

### E2E Encryption (App-Vault)

The Android app implements end-to-end encryption for app-vault communication:

**Session Establishment:**
1. App generates X25519 keypair
2. Sends public key in `app.bootstrap` request
3. Vault responds with its public key
4. Both derive shared session key via ECDH + HKDF

**Message Encryption:**
```kotlin
// SessionCrypto handles all encryption
val session = SessionCrypto.fromKeyExchange(
    sessionId = sessionInfo.sessionId,
    appPrivateKey = keyPair.privateKey,
    appPublicKey = keyPair.publicKey,
    vaultPublicKey = vaultPublicKey,
    expiresAt = expiresAt
)

// Encrypt message
val encrypted = session.encrypt(payload.toByteArray())
// Returns: EncryptedSessionMessage(sessionId, ciphertext, nonce)

// Decrypt response
val decrypted = session.decrypt(encryptedMessage)
```

**Key Files:**
- `core/crypto/SessionCrypto.kt` - Session encryption/decryption
- `core/nats/OwnerSpaceClient.kt` - Bootstrap and session management
- `core/storage/CredentialStore.kt` - Session storage

---

## Issues

### Open

*No open issues*

### Resolved

#### App-Vault E2E Encryption Implemented
**Status:** ✅ Completed (2025-12-30)
**Affected:** `SessionCrypto.kt`, `OwnerSpaceClient.kt`, `NatsAutoConnector.kt`, `CredentialStore.kt`

Implemented end-to-end encryption for app-vault NATS communication:
- ✅ X25519 ECDH key exchange during `app.bootstrap`
- ✅ HKDF-SHA256 session key derivation ("app-vault-session-v1")
- ✅ ChaCha20-Poly1305 message encryption (AES-GCM fallback on older Android)
- ✅ Encrypted payload in sendToVault (bootstrap messages excluded)
- ✅ Automatic decryption of incoming vault messages
- ✅ Session persistence in EncryptedSharedPreferences
- ✅ Session restoration on app restart
- ✅ 25 unit tests for SessionCrypto

#### NATS Message Format Mismatch
**Status:** ✅ Fixed (2025-12-22)
**Affected:** `OwnerSpaceClient.kt`, `VaultEventClient.kt`

The Android NATS client was sending messages with field names that didn't match vault-manager. Fixed:
- ✅ `requestId` → `id` in VaultMessage
- ✅ `timestamp` Long → ISO 8601 string
- ✅ Removed `events.` prefix from subject construction
- ✅ Response parsing uses `event_id` with fallback to `id` and legacy `requestId`
- ✅ Added backwards-compatible handling for all field name variations
