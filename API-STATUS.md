# VettID API Status

**Last Updated:** 2025-11-27 by Backend

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
| GET /member/vaults/{id}/status | Not Started | Phase 5 |
| POST /member/vaults/{id}/start | Not Started | Phase 5 |
| POST /member/vaults/{id}/stop | Not Started | Phase 5 |

---

## Recent Changes

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
  - [ ] Android: Implement new multi-step enrollment flow
  - [ ] Android: Implement action-specific authentication flow
  - [ ] Android: Update crypto to handle UTK encryption

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

### Android Implementation Notes

**Current VaultMessage (needs fix):**
```kotlin
// WRONG - field names don't match vault-manager
data class VaultMessage(
    val requestId: String,  // Should be "id"
    val type: String,
    val payload: JsonObject,
    val timestamp: Long     // Should be ISO 8601 string
)
```

**Corrected VaultMessage:**
```kotlin
data class VaultMessage(
    val id: String,
    val type: String,
    val payload: JsonObject,
    val timestamp: String  // ISO 8601 format
) {
    companion object {
        fun create(type: String, payload: JsonObject): VaultMessage {
            return VaultMessage(
                id = UUID.randomUUID().toString(),
                type = type,
                payload = payload,
                timestamp = Instant.now().toString()  // ISO 8601
            )
        }
    }
}
```

**Current subject construction (needs fix):**
```kotlin
// WRONG - adds "events." prefix
messageType = "events.${event.type}"
```

**Corrected subject:**
```kotlin
// CORRECT - use the event type directly
messageType = event.type
```

### Testing NATS Communication

1. **Ensure vault is online** - Check `/member/vault/status` returns `running`
2. **Get NATS credentials** - Call `/vault/nats/token` to get fresh credentials
3. **Connect to NATS** - Use credentials to connect to `nats.vettid.dev:4222`
4. **Subscribe to responses** - `OwnerSpace.{user_guid}.forApp.>`
5. **Send test event** - Publish to `OwnerSpace.{user_guid}.forVault.profile.get`
6. **Verify response** - Check `event_id` matches your request `id`

---

## Issues

### Open

#### NATS Message Format Mismatch
**Status:** Action Required
**Affected:** `OwnerSpaceClient.kt`, `VaultEventClient.kt`

The Android NATS client sends messages with field names that don't match what vault-manager expects:
- `requestId` should be `id`
- `timestamp` (Long) should be ISO 8601 string
- Subject prefix `events.` should be removed

See "Android Implementation Notes" above for fix.

### Resolved

*No resolved issues yet*
