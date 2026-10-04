/**
 * Suite 2 cryptography of VAULT-MESSAGING 0.10.0 §4 (HPKE MLKEM768X25519 /
 * HKDF-SHA256 / ChaCha20-Poly1305, XChaCha20-Poly1305 sessions, Ed25519,
 * Argon2id), the v2 envelope (§5), the handshake, key schedule, epochs and
 * SAS (§6), invitations (§6.4), the alternate-channel helpers (§11),
 * Protean Credential UTK / reply-key sealing (§3.5.4) and item encodings
 * (§10.7). Pure Kotlin/JVM on BouncyCastle's low-level APIs; no Android
 * dependency. Passes the vettid-vault §16 vectors byte for byte (src/test).
 */
package com.vettid.core.crypto
