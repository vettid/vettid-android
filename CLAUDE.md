# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## IMPORTANT: Always Use Scripts First

**Before running individual AWS, adb, or other CLI commands, ALWAYS check for existing scripts in the `scripts/` directory.** This project has well-tested scripts for common operations like:
- Vault decommission/reset
- Deployment
- Log viewing

Running individual commands (like `aws s3 rm` or direct Lambda invocations) instead of using the proper scripts can cause incomplete operations, data inconsistencies, or missed cleanup steps. The scripts handle all edge cases and proper sequencing.

## Build Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK (ProGuard enabled)
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run single test class
./gradlew test --tests "com.vettid.app.ExampleTest"

# Run instrumented tests
./gradlew connectedAndroidTest

# Lint checks
./gradlew lint

# Clean build
./gradlew clean
```

## Architecture

**Stack**: Kotlin 1.9, Jetpack Compose, Hilt DI, Retrofit, Tink, Argon2, Coroutines

**Package structure** (`app/src/main/java/com/vettid/app/`):
- `core/crypto/` - CryptoManager handles X25519 key exchange, ChaCha20-Poly1305 encryption, Argon2id hashing, HKDF key derivation. Hardware-backed EC keys in Android Keystore for attestation.
- `core/attestation/` - HardwareAttestationManager generates hardware-backed attestation certificates. Supports StrongBox (Pixel 3+), TEE, and software fallback.
- `core/storage/` - CredentialStore uses EncryptedSharedPreferences for credential persistence (encrypted blob, UTK pool, LAT, password salt).
- `core/network/` - ApiClient wraps Retrofit for VettID Ledger Service API (multi-step enrollment, action-based auth).
- `features/auth/` - BiometricAuthManager handles fingerprint/face authentication with device credential fallback.
- `di/` - Hilt AppModule provides singleton instances of core managers.
- `ui/` - Compose screens and theme.

**Navigation flow** (VettIDApp.kt): Welcome → Enrollment (QR scan) → Authentication (biometric) → Main (vault/credentials/settings tabs)

## Key Ownership Model

**Ledger (Backend) Owns:**
- CEK (Credential Encryption Key) - X25519 private key for encrypting credential blob
- LTK (Ledger Transaction Key) - X25519 private key for decrypting password hashes

**Mobile Stores:**
- Encrypted credential blob (opaque, cannot decrypt locally)
- UTK pool (User Transaction Keys) - X25519 **public** keys for encrypting password
- LAT (Ledger Auth Token) - 256-bit hex token for verifying server authenticity
- Password salt - For Argon2id hashing

## API Endpoints

Base URL: `https://api.vettid.com/`

**Enrollment (Multi-Step):**
- `POST /api/v1/enroll/start` - Start enrollment with invitation code
- `POST /api/v1/enroll/set-password` - Set encrypted password hash
- `POST /api/v1/enroll/finalize` - Complete enrollment, receive credential package

**Authentication (Action-Specific):**
- `POST /api/v1/action/request` - Request scoped action token (requires Cognito token)
- `POST /api/v1/auth/execute` - Execute auth with action token (NOT Cognito token)

**Vault (Phase 5 - Not Yet Deployed):**
- `GET /member/vaults/{id}/status` - Vault status
- `POST /member/vaults/{id}/start` - Start vault
- `POST /member/vaults/{id}/stop` - Stop vault

## Crypto Flow

**Password Encryption (for API):**
1. Hash password: `Argon2id(password, salt)` → 32-byte hash
2. Generate ephemeral X25519 keypair
3. Compute shared secret: `X25519(ephemeral_private, UTK_public)`
4. Derive key: `HKDF-SHA256(shared, "password-encryption")` → 32-byte key
5. Encrypt: `ChaCha20-Poly1305(password_hash, key, nonce)`
6. Send: `encrypted_password_hash`, `ephemeral_public_key`, `nonce`, `key_id`

## Platform Requirements

- minSdk 26 (Android 8.0)
- targetSdk 34
- Java 17
- Supports GrapheneOS and other security-focused ROMs with hardware-backed Keystore

## Development Scripts

### Vault Decommission (Reset for Re-enrollment)

Use this to completely reset a user's vault for testing re-enrollment:

```bash
# Decommission by user_guid
./scripts/decommission-vault.sh af44310d-2051-46a1-afd8-ee275b53f804

# Decommission and clear app data
./scripts/decommission-vault.sh af44310d-2051-46a1-afd8-ee275b53f804 --clear-app

# Auto-detect user_guid from device logs and clear app
./scripts/decommission-vault.sh --from-device --clear-app
```

This script:
1. Calls the decommission Lambda to clear backend data (DynamoDB, S3)
2. Sends NATS message via SSM to clear enclave credential
3. Optionally clears app data on connected device

**Prerequisites:** AWS CLI configured, Lambda/SSM access, adb for device operations

### Quick Device Commands

```bash
# Install and launch app
./gradlew installProductionDebug && adb shell am start -n com.vettid.app/.MainActivity

# Clear app data only (keeps vault on server)
adb shell pm clear com.vettid.app

# View enrollment logs
adb logcat -s EnrollmentWizardVM:* NitroEnrollmentClient:* NitroAttestation:*

# Test deep link enrollment
adb shell am start -d "vettid://enroll/TEST123"
```

