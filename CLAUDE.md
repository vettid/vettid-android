# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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

**Stack**: Kotlin 1.9, Jetpack Compose, Hilt DI, Retrofit, Coroutines

**Package structure** (`app/src/main/java/com/vettid/app/`):
- `core/crypto/` - CryptoManager handles ECDH key exchange (secp256r1), AES-GCM encryption, ECDSA signing. Keys stored in Android Keystore with optional biometric protection.
- `core/attestation/` - HardwareAttestationManager generates hardware-backed attestation certificates. Supports StrongBox (Pixel 3+), TEE, and software fallback.
- `core/storage/` - CredentialStore uses EncryptedSharedPreferences for Protean Credential persistence (LAT tokens, transaction keys).
- `core/network/` - ApiClient wraps Retrofit for VettID Ledger Service API (enrollment, auth, vault operations, key rotation).
- `features/auth/` - BiometricAuthManager handles fingerprint/face authentication with device credential fallback.
- `di/` - Hilt AppModule provides singleton instances of core managers.
- `ui/` - Compose screens and theme.

**Navigation flow** (VettIDApp.kt): Welcome → Enrollment (QR scan) → Authentication (biometric) → Main (vault/credentials/settings tabs)

**Key concepts**:
- **LAT (Lightweight Authentication Token)**: 32-byte token rotated on each authentication
- **CEK (Credential Encryption Key)**: ECDH key pair for encrypting credential data
- **Transaction Keys**: Pre-generated one-time-use keys for vault operations

## API Endpoints

Base URL: `https://api.vettid.com/`
- `POST /v1/enroll` - Device enrollment with attestation
- `POST /v1/auth` - Authenticate with LAT + signature
- `GET /v1/vaults/{vaultId}/status` - Vault status
- `POST /v1/vaults/{vaultId}/actions` - Vault control (start/stop/restart/terminate)
- `POST /v1/keys/cek/rotate` - CEK rotation
- `POST /v1/keys/tk/replenish` - Replenish transaction keys

## Platform Requirements

- minSdk 26 (Android 8.0)
- targetSdk 34
- Java 17
- Supports GrapheneOS and other security-focused ROMs with hardware-backed Keystore
