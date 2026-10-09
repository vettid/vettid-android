package com.vettid.core.data.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Randomness
import android.hardware.biometrics.BiometricManager.Authenticators
import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.PreferencesRepository
import com.vettid.core.keystore.AppDataKey
import com.vettid.core.keystore.KeystoreException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/** The app lock's state (ANDROID-PLAN D6). */
enum class AppLockState {
    /** Preferences not read yet. */
    PENDING,

    /** The app lock is off. */
    DISABLED,

    /** On, and the member has not authenticated since the app started or timed out. */
    LOCKED,

    /** On, and authenticated: the app-data key is in memory. */
    UNLOCKED,
}

/**
 * What BiometricPrompt allows for [this] method: a class 3 biometric or the phone's screen lock, or the screen lock
 * only (BiometricPrompt with a CryptoObject and DEVICE_CREDENTIAL alone needs API 30; minSdk is 31).
 */
fun AppLockMethod.authenticators(): Int = when (this) {
    AppLockMethod.BIOMETRICS -> Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL
    AppLockMethod.SCREEN_LOCK -> Authenticators.DEVICE_CREDENTIAL
}

/** The user-authenticated Keystore key (`:core:keystore` [AppDataKey]); an interface so tests can use a software key. */
interface AppLockKeys {
    /** A fresh key for [method]: biometric or screen lock, or the screen lock only. */
    fun create(method: AppLockMethod)

    fun exists(): Boolean

    fun encryptCipher(): Cipher

    fun decryptCipher(iv: ByteArray): Cipher

    fun delete()
}

/**
 * [AppLockKeys] on the Android Keystore, per use: [AppLockMethod.BIOMETRICS] class 3 biometric or device credential,
 * invalidated by a new biometric; [AppLockMethod.SCREEN_LOCK] the device credential only.
 */
class KeystoreAppLockKeys(private val key: AppDataKey = AppDataKey()) : AppLockKeys {
    override fun create(method: AppLockMethod) {
        key.create(authTimeoutSeconds = 0, allowDeviceCredential = true, allowBiometric = method == AppLockMethod.BIOMETRICS)
    }

    override fun exists(): Boolean = key.exists()

    override fun encryptCipher(): Cipher = key.encryptCipher()

    override fun decryptCipher(iv: ByteArray): Cipher = key.decryptCipher(iv)

    override fun delete() = key.delete()
}

/** Where the wrapped app-data key is kept (a file in no-backup storage). */
interface WrappedKeyFile {
    fun read(): ByteArray?

    fun write(b: ByteArray)

    fun delete()
}

class FileWrappedKeyFile(private val file: File) : WrappedKeyFile {
    override fun read(): ByteArray? = if (file.exists()) file.readBytes() else null

    override fun write(b: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(b)
    }

    override fun delete() {
        file.delete()
    }
}

/**
 * The app lock (ANDROID-PLAN D6; 0.1.19: "App lock" with a [method]). When on, opening the app, and
 * returning to it after [AppLockTimeout], asks through BiometricPrompt for a class 3 biometric or the
 * device credential ([AppLockMethod.BIOMETRICS]) or the device credential only ([AppLockMethod.SCREEN_LOCK]),
 * whose CryptoObject unwraps the
 * app-data key: a random 32-byte key that the app's local caches are
 * encrypted under (Room caches arrive with A4/A5). A convenience layer only:
 * it never replaces the vault PIN or the credential password, and nothing of
 * the vault depends on it.
 *
 * The activity hands the ciphers from [cipherToEnable] / [cipherToUnlock] to
 * BiometricPrompt and the authenticated cipher back to [completeEnable] /
 * [completeUnlock]. A biometric enrolled since a [AppLockMethod.BIOMETRICS] key was made invalidates
 * it: the lock then turns itself off ([invalidated]) and the member can turn
 * it on again. A screen-lock key that no longer opens (the screen lock was removed) turns the lock off too,
 * without that notice.
 */
@Suppress("TooManyFunctions")
class AppLock(
    private val prefs: PreferencesRepository,
    private val keys: AppLockKeys,
    private val wrapped: WrappedKeyFile,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val stateFlow = MutableStateFlow(AppLockState.PENDING)
    val state: StateFlow<AppLockState> = stateFlow.asStateFlow()

    private val invalidatedFlow = MutableStateFlow(false)

    /** True once the key was invalidated by a biometric change and the lock turned itself off (Biometrics only). */
    val invalidated: StateFlow<Boolean> = invalidatedFlow.asStateFlow()

    private val methodFlow = MutableStateFlow(AppLockMethod.BIOMETRICS)

    /** How the lock asks; the chosen one also while the lock is off. */
    val method: StateFlow<AppLockMethod> = methodFlow.asStateFlow()

    /** The method of the key [cipherToEnable] made, saved by [completeEnable]. */
    private var enabling: AppLockMethod? = null

    private var dataKey: ByteArray? = null
    private var backgroundAt: Long? = null
    private var timeout: AppLockTimeout = AppLockTimeout.DEFAULT

    /** One prompt at a time, and no automatic prompt after one the member dismissed ([UnlockPromptGate]). */
    val prompts = UnlockPromptGate()

    /** Set while a prompt is showing: the device-credential screen sends the app to the background. */
    @Volatile
    var authenticating: Boolean = false

    /** Reads the preference at start: an enabled lock starts locked. */
    suspend fun start() {
        val p = prefs.preferences.first()
        timeout = p.appLockTimeout
        // A lock turned on before the method could be chosen was the biometric one: it stays "Biometrics".
        if (p.appLockMethod == null && p.appLockEnabled) prefs.setAppLockMethod(AppLockMethod.BIOMETRICS)
        methodFlow.value = p.appLockMethod ?: AppLockMethod.BIOMETRICS
        prompts.newLock()
        stateFlow.value = if (p.appLockEnabled && keys.exists() && wrapped.read() != null) AppLockState.LOCKED else AppLockState.DISABLED
        if (p.appLockEnabled && stateFlow.value == AppLockState.DISABLED) prefs.setAppLockEnabled(false)
    }

    fun setTimeout(t: AppLockTimeout) {
        timeout = t
    }

    /**
     * A fresh key for [method] and its encrypt cipher, to authenticate with BiometricPrompt (with
     * [AppLockMethod.authenticators]) before [completeEnable]. Also how the method of a lock that is on changes.
     */
    fun cipherToEnable(method: AppLockMethod = methodFlow.value): Cipher {
        keys.create(method)
        enabling = method
        return keys.encryptCipher()
    }

    /**
     * The prompt of [cipherToEnable] was cancelled or failed. Its fresh key replaced any earlier one, so a lock that
     * was on (a change of method) cannot open any more: it is turned off, without the biometric notice.
     */
    suspend fun enableCancelled() {
        if (enabling == null) return
        enabling = null
        disable()
    }

    /** Chooses the method while the lock is off (it applies when the lock is turned on). */
    suspend fun chooseMethod(method: AppLockMethod) {
        if (stateFlow.value == AppLockState.UNLOCKED || stateFlow.value == AppLockState.LOCKED) return
        prefs.setAppLockMethod(method)
        methodFlow.value = method
    }

    /** What BiometricPrompt allows for the current method. */
    fun authenticators(): Int = methodFlow.value.authenticators()

    /** Wraps a new app-data key with the authenticated [cipher] and turns the lock on. */
    suspend fun completeEnable(cipher: Cipher) {
        val k = Randomness.bytes(KEY_SIZE)
        val ct = try {
            cipher.doFinal(k)
        } catch (e: GeneralSecurityException) {
            Bytes.wipe(k)
            throw KeystoreException("app lock: cannot wrap", e)
        }
        wrapped.write(Bytes.concat(byteArrayOf(FORMAT), cipher.iv, ct))
        prefs.setAppLockEnabled(true)
        val m = enabling ?: methodFlow.value
        enabling = null
        prefs.setAppLockMethod(m)
        methodFlow.value = m
        dataKey?.let { Bytes.wipe(it) }
        dataKey = k
        invalidatedFlow.value = false
        stateFlow.value = AppLockState.UNLOCKED
    }

    /**
     * The decrypt cipher to authenticate, or null when the lock cannot be used
     * any more (key invalidated by a new biometric, or its file missing): the
     * lock is then turned off.
     */
    suspend fun cipherToUnlock(): Cipher? {
        val blob = wrapped.read()
        // The "new fingerprint or face" notice is the biometric method's only.
        val notice = methodFlow.value == AppLockMethod.BIOMETRICS
        if (blob == null || blob.size < 1 + IV + TAG || blob[0] != FORMAT) {
            disable(invalidated = notice)
            return null
        }
        return try {
            keys.decryptCipher(blob.copyOfRange(1, 1 + IV))
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(invalidated = notice)
            null
        } catch (_: KeystoreException) {
            disable(invalidated = notice)
            null
        }
    }

    /** Unwraps the app-data key with the authenticated [cipher]. Returns false if it does not open. */
    fun completeUnlock(cipher: Cipher): Boolean {
        val blob = wrapped.read() ?: return false
        return try {
            val k = cipher.doFinal(blob, 1 + IV, blob.size - 1 - IV)
            dataKey?.let { Bytes.wipe(it) }
            dataKey = k
            stateFlow.value = AppLockState.UNLOCKED
            true
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    /** Turns the lock off: deletes the key and the wrapped data key. */
    suspend fun disable(invalidated: Boolean = false) {
        keys.delete()
        wrapped.delete()
        dataKey?.let { Bytes.wipe(it) }
        dataKey = null
        prefs.setAppLockEnabled(false)
        invalidatedFlow.value = invalidated
        stateFlow.value = AppLockState.DISABLED
    }

    /** As on a fresh install (the wipe of a replaced phone): off, no key, the default timeout. */
    suspend fun reset() {
        disable()
        methodFlow.value = AppLockMethod.BIOMETRICS
        enabling = null
        timeout = AppLockTimeout.DEFAULT
        backgroundAt = null
        authenticating = false
    }

    fun acknowledgeInvalidated() {
        invalidatedFlow.value = false
    }

    /** Locks now (the key leaves memory). */
    fun lockNow() {
        if (stateFlow.value != AppLockState.UNLOCKED) return
        dataKey?.let { Bytes.wipe(it) }
        dataKey = null
        prompts.newLock()
        stateFlow.value = AppLockState.LOCKED
    }

    fun onBackground() {
        if (authenticating) return
        backgroundAt = now()
    }

    fun onForeground() {
        if (authenticating) return
        val at = backgroundAt ?: return
        backgroundAt = null
        if (stateFlow.value == AppLockState.UNLOCKED && now() - at >= timeout.seconds * MS) lockNow()
    }

    /** The app-data key while unlocked (a copy; the caller wipes it), else null. */
    fun appDataKey(): ByteArray? = dataKey?.copyOf()

    private companion object {
        const val KEY_SIZE = 32
        const val FORMAT: Byte = 0x01
        const val IV = 12
        const val TAG = 16
        const val MS = 1000L
    }
}
