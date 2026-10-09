package com.vettid.core.data.lock

import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.AppPreferences
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** A software stand-in for the biometric-gated Keystore key. */
private class SoftKeys : AppLockKeys {
    var key: SecretKey? = null
    val created = mutableListOf<AppLockMethod>()

    override fun create(method: AppLockMethod) {
        created += method
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun exists() = key != null

    override fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun decryptCipher(iv: ByteArray): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv)) }

    override fun delete() {
        key = null
    }
}

private class MemFile : WrappedKeyFile {
    var b: ByteArray? = null

    override fun read() = b?.copyOf()

    override fun write(b: ByteArray) {
        this.b = b.copyOf()
    }

    override fun delete() {
        b = null
    }
}

class AppLockTest {
    private var clock = 0L
    private val prefs = InMemoryPreferencesRepository()
    private val keys = SoftKeys()
    private val file = MemFile()
    private fun lock() = AppLock(prefs, keys, file) { clock }

    @Test
    fun offByDefault() = runTest {
        val l = lock()
        l.start()
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertNull(l.appDataKey())
    }

    @Test
    fun enableThenRestartStartsLockedAndUnlocks() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        val k = l.appDataKey()!!
        assertTrue(prefs.current.value.appLockEnabled)

        val again = lock()
        again.start()
        assertEquals(AppLockState.LOCKED, again.state.value)
        assertNull(again.appDataKey())
        assertTrue(again.completeUnlock(again.cipherToUnlock()!!))
        assertEquals(AppLockState.UNLOCKED, again.state.value)
        assertTrue(k.contentEquals(again.appDataKey()))
    }

    @Test
    fun timeoutLocksOnlyAfterTheChosenTime() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.ONE_MINUTE)
        l.onBackground()
        clock += 59_000
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        l.onBackground()
        clock += 60_000
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun promptsDoNotCountAsLeavingTheApp() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.IMMEDIATELY)
        l.authenticating = true
        l.onBackground()
        clock += 5_000
        l.onForeground()
        l.authenticating = false
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun invalidatedKeyTurnsTheLockOff() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.lockNow()
        file.b = null // the wrapped key is gone (or the key was invalidated)
        assertNull(l.cipherToUnlock())
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertTrue(l.invalidated.value)
        assertFalse(prefs.current.value.appLockEnabled)
    }

    @Test
    fun disableForgetsEverything() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.disable()
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(keys.exists())
        assertNull(file.b)
        assertNotNull(prefs.current.value)
        // The chosen method stays for the next time the lock is turned on.
        assertEquals(AppPreferences(appLockMethod = AppLockMethod.BIOMETRICS), prefs.current.value)
    }

    // --- the method (owner request 2026-10-09, ANDROID-PLAN 0.1.19) ---

    @Test
    fun biometricsIsTheDefaultAndPromptsAllowTheScreenLockToo() = runTest {
        val l = lock()
        l.start()
        assertEquals(AppLockMethod.BIOMETRICS, l.method.value)
        assertEquals(
            android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            l.authenticators(),
        )
        assertNull(prefs.current.value.appLockMethod)
    }

    @Test
    fun screenLockMakesAScreenLockKeyAndPromptsForTheScreenLockOnly() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable(AppLockMethod.SCREEN_LOCK))
        assertEquals(listOf(AppLockMethod.SCREEN_LOCK), keys.created)
        assertEquals(AppLockMethod.SCREEN_LOCK, l.method.value)
        assertEquals(AppLockMethod.SCREEN_LOCK, prefs.current.value.appLockMethod)
        assertEquals(android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL, l.authenticators())
        // Kept across a restart.
        val again = lock()
        again.start()
        assertEquals(AppLockMethod.SCREEN_LOCK, again.method.value)
        assertEquals(AppLockState.LOCKED, again.state.value)
    }

    @Test
    fun aLockTurnedOnBeforeTheChoiceExistedIsBiometrics() = runTest {
        // As an older build left it: on, a key and its wrapped data key, no method stored.
        val old = lock()
        old.start()
        old.completeEnable(old.cipherToEnable())
        prefs.clear()
        prefs.setAppLockEnabled(true)
        assertNull(prefs.current.value.appLockMethod)
        val l = lock()
        l.start()
        assertEquals(AppLockMethod.BIOMETRICS, l.method.value)
        assertEquals(AppLockMethod.BIOMETRICS, prefs.current.value.appLockMethod)
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun theMethodIsChosenWhileOffAndUsedWhenTurnedOn() = runTest {
        val l = lock()
        l.start()
        l.chooseMethod(AppLockMethod.SCREEN_LOCK)
        assertEquals(AppLockMethod.SCREEN_LOCK, prefs.current.value.appLockMethod)
        l.completeEnable(l.cipherToEnable())
        assertEquals(listOf(AppLockMethod.SCREEN_LOCK), keys.created)
    }

    @Test
    fun aChangeOfMethodThatIsCancelledTurnsTheLockOff() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable(AppLockMethod.BIOMETRICS))
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        l.enableCancelled()
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(l.invalidated.value)
        assertFalse(prefs.current.value.appLockEnabled)
    }

    @Test
    fun theFingerprintNoticeIsTheBiometricMethodsOnly() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable(AppLockMethod.SCREEN_LOCK))
        file.b = null // the key no longer opens (the screen lock was removed)
        assertNull(l.cipherToUnlock())
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(l.invalidated.value)
    }
}
