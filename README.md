# VettID Android

Privacy-first digital identity app for Android.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

## Overview

VettID gives you complete control over your digital identity through hardware-secured vaults. Your personal data is encrypted and stored in AWS Nitro Enclaves - even VettID cannot access your information.

## Features

- **Secure Enrollment** - QR code-based credential setup
- **Hardware Security** - Keys stored in Android Keystore (TEE/StrongBox)
- **Biometric Auth** - Fingerprint and face authentication
- **E2E Encryption** - X25519 + XChaCha20-Poly1305
- **Vault Communication** - Real-time NATS messaging
- **PCR Attestation** - Verify enclave integrity

## Requirements

- Android 8.0+ (API 26)
- Kotlin 1.9+
- Android Studio Hedgehog or later

## Project Structure

```
app/src/main/java/com/vettid/app/
├── core/
│   ├── crypto/           # X25519, Ed25519, encryption
│   ├── storage/          # Keystore, secure storage
│   ├── network/          # API client
│   ├── nats/             # NATS messaging client
│   └── attestation/      # Hardware Key Attestation
├── features/
│   ├── enrollment/       # QR scanning, credential setup
│   ├── auth/             # Login, biometrics
│   ├── vault/            # Vault status, commands
│   ├── transfer/         # Credential transfer
│   └── voting/           # Vault-based voting
└── ui/
    ├── screens/          # Compose screens
    └── components/       # Reusable UI components
```

## Build

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
```

## Security

- All cryptographic keys stored in Android Keystore (hardware-backed when available)
- Hardware Key Attestation for device integrity verification
- Supports GrapheneOS and other security-focused Android distributions
- No sensitive data in SharedPreferences or plain files

## Supported Platforms

- Stock Android with Google attestation
- GrapheneOS (with hardware attestation support)
- Other ROMs with hardware-backed Keystore

## Related Repositories

- [vettid-dev](https://github.com/vettid/vettid-dev) - Backend infrastructure
- [vettid-ios](https://github.com/vettid/vettid-ios) - iOS app
- [vettid-desktop](https://github.com/vettid/vettid-desktop) - Desktop app (Tauri/Rust/Svelte)
- [vettid-agent](https://github.com/vettid/vettid-agent) - Agent connector (Go sidecar)
- [vettid-service-vault](https://github.com/vettid/vettid-service-vault) - Service integration layer
- [vettid.org](https://github.com/vettid/vettid.org) - Website

## License

AGPL-3.0-or-later - See [LICENSE](LICENSE) for details.

## Links

- Website: [vettid.org](https://vettid.org)
- Documentation: [docs.vettid.dev](https://docs.vettid.dev)
