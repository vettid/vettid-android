package com.vettid.core.keystore

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Computes the phone key's HMAC-SHA-256 over a message, in the phone's hardware (VAULT-MESSAGING 0.33.0 §11.14.1).
 * An interface so that unit tests and the TEST attester's phones use a software key.
 */
fun interface PhoneSecretSource {
    fun hmac(message: ByteArray): ByteArray
}

/** A phone key that can be made, checked and removed ([PhoneKey]; a software one in tests). */
interface PhoneKeyHandle : PhoneSecretSource {
    /** Makes a new key (an existing one is replaced); refuses one in software ([KeystoreException]). */
    fun create(): KeyLevel

    fun exists(): Boolean

    fun delete()
}

/**
 * The phone key of release R (VAULT-MESSAGING 0.33.0 §11.14.1, ANDROID-PLAN 0.1.40): a non-exportable HMAC-SHA-256
 * key in the Android Keystore under `vault-unlock-<vault_id>`, StrongBox first and the TEE otherwise, exactly like
 * the device attestation key and the app key: **not** bound to user authentication or to an unlocked screen, so
 * that it is lost together with the other two keys or not at all. It computes the unlock secret
 * ([com.vettid.core.crypto.altchan.UnlockSecret]) each time one is needed; the secret is never stored.
 */
class PhoneKey(vaultId: String) : PhoneKeyHandle {
    val alias: String = ALIAS_PREFIX + vaultId

    /**
     * Makes a new key under the alias (an existing one is replaced: enrollment, a transfer to this phone, a
     * recovery). Refuses a key the Keystore reports at [KeyLevel.SOFTWARE]: the app then does not enroll, receive a
     * transfer or recover ("This phone cannot keep your vault's keys in its secure hardware").
     */
    override fun create(): KeyLevel = create(preferStrongBox = true)

    @Synchronized
    fun create(preferStrongBox: Boolean): KeyLevel {
        AndroidKeys.delete(alias)
        val (key, _) = AndroidKeys.generateStrongBoxFirst(preferStrongBox) { sb ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setIsStrongBoxBacked(sb)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, AndroidKeys.PROVIDER).apply { init(spec) }.generateKey()
        }
        val level = AndroidKeys.secretKeyLevel(key)
        if (level == KeyLevel.SOFTWARE) {
            AndroidKeys.delete(alias)
            throw KeystoreException("phone key in software")
        }
        return level
    }

    override fun exists(): Boolean = AndroidKeys.exists(alias)

    /** Where the key lives, as the Keystore reports it. */
    fun level(): KeyLevel = AndroidKeys.secretKeyLevel(key())

    override fun hmac(message: ByteArray): ByteArray = Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
        init(key())
        doFinal(message)
    }

    override fun delete() = AndroidKeys.delete(alias)

    private fun key(): SecretKey = AndroidKeys.keyStore().getKey(alias, null) as? SecretKey ?: throw KeystoreException("no phone key")

    companion object {
        const val ALIAS_PREFIX = "vault-unlock-"
    }
}
