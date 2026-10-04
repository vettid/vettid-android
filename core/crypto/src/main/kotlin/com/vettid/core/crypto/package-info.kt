/**
 * Suite 2 cryptography (HPKE MLKEM768X25519 / HKDF-SHA256 /
 * ChaCha20-Poly1305), XChaCha20 sessions, Ed25519, Argon2id; envelope v2;
 * handshake and epochs; credential UTK/reply-key sealing. Must pass the
 * vettid-vault test vectors byte for byte. Phase A1.
 *
 * Empty in phase A0; filled in by the phase noted above (ANDROID-PLAN §5, §6).
 */
package com.vettid.core.crypto
