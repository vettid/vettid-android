/**
 * Android Keystore keys of the app (ANDROID-PLAN §5, VAULT-MESSAGING §3.2,
 * §11.7):
 * - [DeviceAttestationKey]: EC P-256 with an attestation challenge, StrongBox
 *   first, TEE fallback; signs unlock challenges and release approvals;
 * - [SeedWrapKey] + [SeedWrapper] + [DeviceKeys]: the relay, identity and KEM
 *   seeds, stored only wrapped under a non-exportable AES-GCM Keystore key;
 * - [AppDataKey]: the biometric-gated (class 3 or device credential) key of
 *   the app lock (D6).
 */
package com.vettid.core.keystore
