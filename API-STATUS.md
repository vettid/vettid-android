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

## Issues

### Open

*No open issues*

### Resolved

*No resolved issues yet*
