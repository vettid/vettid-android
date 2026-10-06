package com.vettid.core.keystore

import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Where a Keystore key lives. */
enum class KeyLevel { STRONG_BOX, TEE, SOFTWARE, UNKNOWN }

/** The Android Keystore keys of the app. Aliases are fixed; every key is non-exportable. */
object AndroidKeys {
    const val PROVIDER = "AndroidKeyStore"
    const val ALIAS_DEVICE_ATTESTATION = "vettid.device_attestation.v1"
    const val ALIAS_SEED_WRAP = "vettid.seed_wrap.v1"
    const val ALIAS_APP_DATA = "vettid.app_data.v1"
    const val ALIAS_APP_API = "vettid.app_api.v1"

    internal fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(alias: String): Boolean = keyStore().containsAlias(alias)

    fun delete(alias: String) = keyStore().deleteEntry(alias)

    /** Every alias in this app's Keystore (the Keystore is per app: all of them are the app's). */
    fun aliases(): List<String> = keyStore().aliases().toList()

    /** Whether the device has StrongBox (a secure element) for keys. */
    fun hasStrongBox(pm: PackageManager): Boolean = pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    /**
     * Generates with StrongBox when [strongBox] is set and the device supports
     * it for this key type, else in the TEE. Returns the level actually used.
     */
    internal fun <T> generateStrongBoxFirst(strongBox: Boolean, gen: (Boolean) -> T): Pair<T, Boolean> {
        if (strongBox) {
            try {
                return gen(true) to true
            } catch (_: StrongBoxUnavailableException) {
                // No StrongBox, or not for this key type: falls through to the TEE.
            }
        }
        return gen(false) to false
    }

    @Suppress("DEPRECATION")
    internal fun levelOf(info: KeyInfo): KeyLevel = when (info.securityLevel) {
        KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyLevel.STRONG_BOX
        KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyLevel.TEE
        KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyLevel.SOFTWARE
        else -> if (info.isInsideSecureHardware) KeyLevel.TEE else KeyLevel.UNKNOWN
    }

    internal fun privateKeyLevel(key: PrivateKey): KeyLevel =
        levelOf(KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java))

    internal fun secretKeyLevel(key: SecretKey): KeyLevel =
        levelOf(SecretKeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java) as KeyInfo)
}

/**
 * The device attestation key (VAULT-MESSAGING §11.7): a non-exportable EC
 * P-256 signing key generated with `setAttestationChallenge(challenge)`, in
 * StrongBox when the device has it and in the TEE otherwise. Its certificate
 * chain goes to the enclave at enrollment (and at a transfer or recovery); at
 * every unlock it signs the request's challenge (SHA256withECDSA, DER), and an
 * approval of a release update signs the approval string (§11.10.3).
 *
 * The key needs no user authentication: the vault PIN gates unlocks, and the
 * enclave checks the signature. It is not bound to an unlocked device either,
 * so an unlock can be signed from a notification while the phone is locked.
 */
class DeviceAttestationKey(private val alias: String = AndroidKeys.ALIAS_DEVICE_ATTESTATION) {
    /**
     * Generates a fresh key bound to [challenge] (§11.7, 32 bytes) and returns
     * its certificate chain, leaf first. An existing key under the alias is
     * replaced: a new attestation is a new key.
     */
    fun generate(challenge: ByteArray, preferStrongBox: Boolean = true): AttestedKey {
        require(challenge.size == CHALLENGE_SIZE) { "challenge must be 32 bytes" }
        AndroidKeys.delete(alias)
        val (_, strongBox) = AndroidKeys.generateStrongBoxFirst(preferStrongBox) { sb ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(sb)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, AndroidKeys.PROVIDER).apply { initialize(spec) }.generateKeyPair()
        }
        return AttestedKey(chain(), level(), strongBox)
    }

    fun exists(): Boolean = AndroidKeys.exists(alias)

    /** The stored certificate chain, leaf first (the leaf carries the attestation extension). */
    fun chain(): List<X509Certificate> =
        AndroidKeys.keyStore().getCertificateChain(alias)?.map { it as X509Certificate } ?: throw KeystoreException("no attestation key")

    /** Signs [message] with SHA256withECDSA (DER), as §11.7 and §11.10.3 require. */
    fun sign(message: ByteArray): ByteArray {
        val key = AndroidKeys.keyStore().getKey(alias, null) as? PrivateKey ?: throw KeystoreException("no attestation key")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    fun level(): KeyLevel {
        val key = AndroidKeys.keyStore().getKey(alias, null) as? PrivateKey ?: throw KeystoreException("no attestation key")
        return AndroidKeys.privateKeyLevel(key)
    }

    fun delete() = AndroidKeys.delete(alias)

    companion object {
        const val CHALLENGE_SIZE = 32
    }
}

/**
 * The app key (VAULT-MESSAGING §11.12.2, 0.15.0): a non-exportable EC P-256 signing key, in StrongBox when the
 * device has it and in the TEE otherwise, that signs every app request to the member API (`X-VettID-App`). It is
 * made per vault (a new setup, recovery or transfer on this phone makes a new one, [regenerate]) and is distinct
 * from the device attestation key, whose chain stays inside the enclave. No user authentication and no
 * unlocked-device binding, so that a lock and the result polling work in the background.
 */
class AppApiKey(private val alias: String = AndroidKeys.ALIAS_APP_API) {
    /** The key, made on first use. */
    @Synchronized
    fun getOrCreate(preferStrongBox: Boolean = true): java.security.PublicKey {
        AndroidKeys.keyStore().getCertificate(alias)?.let { return it.publicKey }
        return generate(preferStrongBox)
    }

    /** A fresh key under the alias (an existing one is replaced). */
    @Synchronized
    fun regenerate(preferStrongBox: Boolean = true): java.security.PublicKey {
        AndroidKeys.delete(alias)
        return generate(preferStrongBox)
    }

    private fun generate(preferStrongBox: Boolean): java.security.PublicKey {
        val (kp, _) = AndroidKeys.generateStrongBoxFirst(preferStrongBox) { sb ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setIsStrongBoxBacked(sb)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, AndroidKeys.PROVIDER).apply { initialize(spec) }.generateKeyPair()
        }
        return kp.public
    }

    fun exists(): Boolean = AndroidKeys.exists(alias)

    /** The public key's SubjectPublicKeyInfo DER (the form the member API and the vault record). */
    fun spki(): ByteArray = getOrCreate().encoded

    /** Signs [message] with SHA256withECDSA (DER). */
    fun sign(message: ByteArray): ByteArray {
        getOrCreate()
        val key = AndroidKeys.keyStore().getKey(alias, null) as? PrivateKey ?: throw KeystoreException("no app key")
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(message)
            sign()
        }
    }

    fun delete() = AndroidKeys.delete(alias)
}

/** A freshly attested key: its chain (leaf first) and where it lives. */
class AttestedKey(val chain: List<X509Certificate>, val level: KeyLevel, val strongBox: Boolean)

/**
 * The AES-256-GCM key that wraps the device seeds ([SeedWrapper]). It lives
 * in the TEE (StrongBox adds latency for no gain here: the seeds are unwrapped
 * once per process). No user authentication and no unlocked-device binding:
 * the relay key must be usable by the background collector while the phone is
 * locked (ANDROID-PLAN §7).
 */
object SeedWrapKey {
    @Synchronized
    fun getOrCreate(alias: String = AndroidKeys.ALIAS_SEED_WRAP): SecretKey {
        (AndroidKeys.keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeys.PROVIDER).apply { init(spec) }.generateKey()
    }

    fun wrapper(alias: String = AndroidKeys.ALIAS_SEED_WRAP): SeedWrapper = SeedWrapper { getOrCreate(alias) }

    fun level(alias: String = AndroidKeys.ALIAS_SEED_WRAP): KeyLevel = AndroidKeys.secretKeyLevel(getOrCreate(alias))
}

/**
 * The biometric-gated app-data key (ANDROID-PLAN D6): an AES-256-GCM key that
 * can be used only right after the member authenticates with a class 3
 * biometric or the device credential (BiometricPrompt), and that is
 * invalidated when a new biometric is enrolled. It wraps the key of the app's
 * local data; the app lock (A3) hands [encryptCipher] / [decryptCipher] to
 * BiometricPrompt as a CryptoObject. A convenience layer only: it never
 * replaces the vault PIN or the credential password.
 */
class AppDataKey(private val alias: String = AndroidKeys.ALIAS_APP_DATA) {
    /**
     * Creates the key. With [authTimeoutSeconds] = 0 every use needs its own
     * authentication (CryptoObject); with a positive value the key stays usable
     * for that long after any authentication.
     */
    fun create(authTimeoutSeconds: Int = 0, allowDeviceCredential: Boolean = true, preferStrongBox: Boolean = false): KeyLevel {
        AndroidKeys.delete(alias)
        val authTypes = KeyProperties.AUTH_BIOMETRIC_STRONG or (if (allowDeviceCredential) KeyProperties.AUTH_DEVICE_CREDENTIAL else 0)
        AndroidKeys.generateStrongBoxFirst(preferStrongBox) { sb ->
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(authTimeoutSeconds, authTypes)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(sb)
                .build()
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeys.PROVIDER).apply { init(spec) }.generateKey()
        }
        return AndroidKeys.secretKeyLevel(key())
    }

    fun exists(): Boolean = AndroidKeys.exists(alias)

    private fun key(): SecretKey = AndroidKeys.keyStore().getKey(alias, null) as? SecretKey ?: throw KeystoreException("no app-data key")

    /**
     * A cipher to wrap the local-data key, for BiometricPrompt.CryptoObject.
     * Throws [android.security.keystore.KeyPermanentlyInvalidatedException]
     * after a biometric change (the app then asks for the vault and recreates
     * the key), and, with a timeout, `UserNotAuthenticatedException` when the
     * member has not authenticated recently.
     */
    fun encryptCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }

    /** A cipher to unwrap, for BiometricPrompt.CryptoObject. [iv] is the 12-byte IV stored with the wrapped key. */
    fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), javax.crypto.spec.GCMParameterSpec(128, iv)) }

    fun delete() = AndroidKeys.delete(alias)

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
