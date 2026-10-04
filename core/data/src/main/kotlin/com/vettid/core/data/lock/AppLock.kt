package com.vettid.core.data.lock

import android.security.keystore.KeyPermanentlyInvalidatedException
import com.vettid.core.crypto.Bytes
import com.vettid.core.crypto.Randomness
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

/** The biometric-gated Keystore key (`:core:keystore` [AppDataKey]); an interface so tests can use a software key. */
interface AppLockKeys {
    fun create()

    fun exists(): Boolean

    fun encryptCipher(): Cipher

    fun decryptCipher(iv: ByteArray): Cipher

    fun delete()
}

/** [AppLockKeys] on the Android Keystore: class 3 biometric or device credential, per use, invalidated by a new biometric. */
class KeystoreAppLockKeys(private val key: AppDataKey = AppDataKey()) : AppLockKeys {
    override fun create() {
        key.create(authTimeoutSeconds = 0, allowDeviceCredential = true)
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
 * The biometric app lock (ANDROID-PLAN D6). When on, opening the app, and
 * returning to it after [AppLockTimeout], asks for a class 3 biometric or the
 * device credential through BiometricPrompt, whose CryptoObject unwraps the
 * app-data key: a random 32-byte key that the app's local caches are
 * encrypted under (Room caches arrive with A4/A5). A convenience layer only:
 * it never replaces the vault PIN or the credential password, and nothing of
 * the vault depends on it.
 *
 * The activity hands the ciphers from [cipherToEnable] / [cipherToUnlock] to
 * BiometricPrompt and the authenticated cipher back to [completeEnable] /
 * [completeUnlock]. A biometric enrolled since the key was made invalidates
 * it: the lock then turns itself off ([invalidated]) and the member can turn
 * it on again.
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

    /** True once the key was invalidated by a biometric change and the lock turned itself off. */
    val invalidated: StateFlow<Boolean> = invalidatedFlow.asStateFlow()

    private var dataKey: ByteArray? = null
    private var backgroundAt: Long? = null
    private var timeout: AppLockTimeout = AppLockTimeout.DEFAULT

    /** Set while a prompt is showing: the device-credential screen sends the app to the background. */
    @Volatile
    var authenticating: Boolean = false

    /** Reads the preference at start: an enabled lock starts locked. */
    suspend fun start() {
        val p = prefs.preferences.first()
        timeout = p.appLockTimeout
        stateFlow.value = if (p.appLockEnabled && keys.exists() && wrapped.read() != null) AppLockState.LOCKED else AppLockState.DISABLED
        if (p.appLockEnabled && stateFlow.value == AppLockState.DISABLED) prefs.setAppLockEnabled(false)
    }

    fun setTimeout(t: AppLockTimeout) {
        timeout = t
    }

    /** A fresh key and its encrypt cipher, to authenticate with BiometricPrompt before [completeEnable]. */
    fun cipherToEnable(): Cipher {
        keys.create()
        return keys.encryptCipher()
    }

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
        if (blob == null || blob.size < 1 + IV + TAG || blob[0] != FORMAT) {
            disable(invalidated = true)
            return null
        }
        return try {
            keys.decryptCipher(blob.copyOfRange(1, 1 + IV))
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable(invalidated = true)
            null
        } catch (_: KeystoreException) {
            disable(invalidated = true)
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

    fun acknowledgeInvalidated() {
        invalidatedFlow.value = false
    }

    /** Locks now (the key leaves memory). */
    fun lockNow() {
        if (stateFlow.value != AppLockState.UNLOCKED) return
        dataKey?.let { Bytes.wipe(it) }
        dataKey = null
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
