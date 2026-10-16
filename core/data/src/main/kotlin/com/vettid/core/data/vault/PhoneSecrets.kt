package com.vettid.core.data.vault

import com.vettid.core.crypto.altchan.UnlockSecret
import com.vettid.core.keystore.KeystoreException
import com.vettid.core.keystore.PhoneKeyHandle

/**
 * The phone's unlock secret for a vault of release R (VAULT-MESSAGING 0.33.0 §11.14.1, ANDROID-PLAN 0.1.40): which
 * key ([keyVault], the vault id it was made for, "" for a transfer's new phone) and which [epoch] (0: this vault
 * has no phone secret, a vault from before release R). The secret itself is computed by the phone key each time
 * and never kept.
 */
data class PhoneSecretState(val keyVault: String = "", val epoch: Long = 0) {
    val inUse: Boolean get() = epoch > 0
}

/** Computes secrets from [keys] (the Keystore's [com.vettid.core.keystore.PhoneKey]; a software key in tests). */
class PhoneSecrets(private val keys: (String) -> PhoneKeyHandle) {
    /**
     * Makes a new phone key for [keyVault] (enrollment, a transfer to this phone, a recovery) and returns the state
     * at epoch 1. A key the Keystore keeps in software is refused ([FailureKind.HARDWARE_KEYS]).
     */
    fun create(keyVault: String): PhoneSecretState {
        try {
            keys(keyVault).create()
        } catch (e: KeystoreException) {
            throw VaultFailure(FailureKind.HARDWARE_KEYS, "phone_key", cause = e)
        }
        return PhoneSecretState(keyVault, 1)
    }

    /** The secret of [s]'s epoch plus [ahead]; [FailureKind.DEVICE_SECRET] when the key is gone (data cleared). */
    @Suppress("ThrowsCount")
    fun secret(s: PhoneSecretState, ahead: Long = 0): ByteArray {
        val k = keys(s.keyVault)
        if (!s.inUse || !k.exists()) throw VaultFailure(FailureKind.DEVICE_SECRET, "no_phone_key")
        return try {
            UnlockSecret.compute(s.keyVault, s.epoch + ahead, k::hmac)
        } catch (e: KeystoreException) {
            throw VaultFailure(FailureKind.DEVICE_SECRET, "no_phone_key", cause = e)
        } catch (e: java.security.GeneralSecurityException) {
            throw VaultFailure(FailureKind.DEVICE_SECRET, "no_phone_key", cause = e)
        }
    }

    /** Removes the key (a wipe, the end of a transfer's keep period). */
    fun delete(s: PhoneSecretState) {
        runCatching { keys(s.keyVault).delete() }
    }
}
