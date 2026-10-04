package com.vettid.core.data.lock

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

    override fun create() {
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
        assertEquals(AppPreferences(), prefs.current.value)
    }
}
