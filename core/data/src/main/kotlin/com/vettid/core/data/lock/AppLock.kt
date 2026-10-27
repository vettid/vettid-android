package com.vettid.core.data.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
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
import java.io.IOException
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

    /** On, and the member authenticated since the app started or timed out. */
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

    /** A cipher whose use proves a fresh authentication (BiometricPrompt CryptoObject; the key needs one per use). */
    fun encryptCipher(): Cipher

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

    override fun delete() = key.delete()
}

/**
 * Where builds before 2026-10-27 kept a random "app-data key" wrapped under the lock's Keystore key (a file in no-backup
 * storage). Nothing used that key (A6 audit, 2026-10-27), so it is gone; [AppLock.start] only deletes the stored file.
 */
interface WrappedKeyFile {
    fun delete()
}

class FileWrappedKeyFile(private val file: File) : WrappedKeyFile {
    override fun delete() {
        file.delete()
    }
}

/**
 * The app lock (ANDROID-PLAN D6; 0.1.19: "App lock" with a [method]). When on, opening the app, and
 * returning to it after [AppLockTimeout], asks through BiometricPrompt for a class 3 biometric or the
 * device credential ([AppLockMethod.BIOMETRICS]) or the device credential only ([AppLockMethod.SCREEN_LOCK]),
 * whose CryptoObject must then encrypt a short fixed proof: the Keystore key is usable only right after
 * an authentication, so a cipher that works is the proof. A UI gate only: it protects no data (there is no
 * app-data key; the one earlier builds wrapped was never used), never replaces the vault PIN or the credential
 * password, and nothing of the vault depends on it.
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
    private val obsoleteWrappedKey: WrappedKeyFile,
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

    private var backgroundAt: Long? = null
    private var timeout: AppLockTimeout = AppLockTimeout.DEFAULT

    /** One prompt at a time, and no automatic prompt after one the member dismissed ([UnlockPromptGate]). */
    val prompts = UnlockPromptGate()

    /** Set while a prompt is showing: the device-credential screen sends the app to the background. */
    @Volatile
    var authenticating: Boolean = false

    /** Reads the preference at start: an enabled lock starts locked. Always leaves [AppLockState.PENDING]. */
    suspend fun start() {
        val p = prefs.preferences.first()
        timeout = p.appLockTimeout
        methodFlow.value = p.appLockMethod ?: AppLockMethod.BIOMETRICS
        prompts.newLock()
        // The wrapped app-data key of earlier builds was never used: deleted at the next start (2026-10-27).
        runCatching { obsoleteWrappedKey.delete() }
        // A key that is gone is a lock that cannot open: off, never "pending" for good (the start screen would
        // stay; A6 audit P4). The lock is a convenience and locks nobody out.
        val usable = p.appLockEnabled && keys.exists()
        stateFlow.value = if (usable) AppLockState.LOCKED else AppLockState.DISABLED
        // Bookkeeping last: a preference that cannot be written must not keep the lock pending.
        try {
            // A lock turned on before the method could be chosen was the biometric one: it stays "Biometrics".
            if (p.appLockMethod == null && p.appLockEnabled) prefs.setAppLockMethod(AppLockMethod.BIOMETRICS)
            if (p.appLockEnabled && !usable) prefs.setAppLockEnabled(false)
        } catch (_: IOException) {
            // Read again at the next start.
        }
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

    /** Checks the authenticated [cipher] works (the proof of the authentication) and turns the lock on. */
    suspend fun completeEnable(cipher: Cipher) {
        try {
            cipher.doFinal(PROOF)
        } catch (e: GeneralSecurityException) {
            throw KeystoreException("app lock: not authenticated", e)
        }
        prefs.setAppLockEnabled(true)
        val m = enabling ?: methodFlow.value
        enabling = null
        prefs.setAppLockMethod(m)
        methodFlow.value = m
        invalidatedFlow.value = false
        stateFlow.value = AppLockState.UNLOCKED
    }

    /**
     * The cipher to authenticate, or null when the lock cannot be used
     * any more (key invalidated by a new biometric, or gone): the
     * lock is then turned off.
     */
    suspend fun cipherToUnlock(): Cipher? {
        // The "new fingerprint or face" notice is the biometric method's only.
        val notice = methodFlow.value == AppLockMethod.BIOMETRICS
        if (!keys.exists()) {
            disable(invalidated = notice)
            return null
        }
        return try {
            keys.encryptCipher()
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(invalidated = notice)
            null
        } catch (_: KeystoreException) {
            disable(invalidated = notice)
            null
        }
    }

    /** Checks the authenticated [cipher] works. Returns false if it does not (the authentication did not happen). */
    fun completeUnlock(cipher: Cipher): Boolean {
        return try {
            cipher.doFinal(PROOF)
            stateFlow.value = AppLockState.UNLOCKED
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: IllegalStateException) {
            false // a cipher that was never initialised
        }
    }

    /** Turns the lock off: deletes the key. */
    suspend fun disable(invalidated: Boolean = false) {
        keys.delete()
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

    /** Locks now. */
    fun lockNow() {
        if (stateFlow.value != AppLockState.UNLOCKED) return
        prompts.newLock()
        stateFlow.value = AppLockState.LOCKED
    }

    /**
     * The activity stopped. Not counted as leaving the app while a prompt shows, nor when the activity is only being
     * recreated for a configuration change ([changingConfigurations]: rotation, fold, theme): with the timeout
     * "Immediately" either would lock the app in the member's hands.
     */
    fun onBackground(changingConfigurations: Boolean = false) {
        if (authenticating || changingConfigurations) return
        backgroundAt = now()
    }

    fun onForeground() {
        if (authenticating) return
        val at = backgroundAt ?: return
        backgroundAt = null
        if (stateFlow.value == AppLockState.UNLOCKED && now() - at >= timeout.seconds * MS) lockNow()
    }

    private companion object {
        /** What the authenticated cipher encrypts: not a secret, only a use of the key that needs the authentication. */
        val PROOF = "vettid/app-lock/1".toByteArray()
        const val MS = 1000L
    }
}
