# VettID Android

VettID Android mobile application for secure credential management and vault access.

## Requirements

- Android 8.0+ (API 26)
- Kotlin 1.9+
- Android Studio Hedgehog or later

## Features

- Protean Credential enrollment via QR code
- Secure credential storage using Android Keystore
- Hardware Key Attestation (supports GrapheneOS)
- Vault deployment and management
- Biometric authentication (Fingerprint / Face)
- X25519 key exchange + XChaCha20-Poly1305 encryption

## Project Structure

```
app/src/main/java/com/vettid/app/
├── VettIDApplication.kt       # Application class
├── MainActivity.kt            # Main activity
├── core/
│   ├── crypto/               # X25519, Ed25519, encryption
│   ├── storage/              # Keystore, secure storage
│   ├── network/              # API client
│   └── attestation/          # Hardware Key Attestation
├── features/
│   ├── enrollment/           # QR scanning, credential setup
│   ├── auth/                 # Login, biometrics
│   └── vault/                # Vault status, commands
└── ui/
    ├── screens/              # Compose screens
    └── components/           # Reusable UI components
```

## Setup

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle files
4. Build and run

## Security

- All cryptographic keys stored in Android Keystore (hardware-backed when available)
- Hardware Key Attestation for device integrity verification
- Supports GrapheneOS and other security-focused Android distributions
- No sensitive data in SharedPreferences or plain files

## Supported Platforms

- Stock Android with Google attestation
- GrapheneOS (with hardware attestation support)
- Other ROMs with hardware-backed Keystore
