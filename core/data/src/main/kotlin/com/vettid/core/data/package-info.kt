/**
 * Repositories and local storage (ANDROID-PLAN §5). Phase A2 adds the
 * device's encrypted storage ([KeystoreFileStore]: the vault client's state
 * and the member session's cookies, AES-256-GCM under a Keystore key) and
 * [VaultSession], which wires the vault client to the Keystore-held device
 * keys. Room caches per feature follow in A3 to A5.
 */
package com.vettid.core.data
