package com.vettid.core.data.lock

import com.vettid.core.data.prefs.AppLockMethod
import com.vettid.core.data.prefs.AppLockTimeout
import com.vettid.core.data.prefs.InMemoryPreferencesRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The slot logic of [KeystoreAppLockKeys] over two software slots (they outlive a "process", like the Keystore). */
class KeystoreAppLockKeysTest {
    private class FakeSlot : AppLockKeySlot {
        var key: SecretKey? = null
        var method: AppLockMethod? = null

        override fun exists() = key != null

        override fun create(method: AppLockMethod) {
            this.method = method
            key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        }

        override fun cipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

        override fun delete() {
            key = null
        }
    }

    private val a = FakeSlot()
    private val b = FakeSlot()
    private fun keys() = KeystoreAppLockKeys(listOf(a, b))

    @Test
    fun aChangeMakesItsKeyInTheOtherSlotAndSwapsAtPromote() {
        val k = keys()
        k.recover(0)
        k.createPending(AppLockMethod.BIOMETRICS)
        k.promote()
        assertTrue(a.exists().not() && b.exists()) // the first key went to the other slot
        k.createPending(AppLockMethod.SCREEN_LOCK)
        assertTrue("both while pending", a.exists() && b.exists())
        assertEquals(0, k.pendingSlot())
        k.promote()
        assertTrue(a.exists() && !b.exists())
        assertEquals(AppLockMethod.SCREEN_LOCK, a.method)
    }

    @Test
    fun aDiscardedPendingKeepsTheCurrentOne() {
        val k = keys()
        k.recover(0)
        k.createPending(AppLockMethod.BIOMETRICS)
        k.promote()
        k.createPending(AppLockMethod.SCREEN_LOCK)
        k.discardPending()
        assertTrue(b.exists() && !a.exists())
        assertTrue(k.exists())
    }

    @Test
    fun bothSlotsWithMarkerZeroKeepZero() {
        a.create(AppLockMethod.BIOMETRICS)
        b.create(AppLockMethod.SCREEN_LOCK)
        val k = keys()
        k.recover(0)
        assertTrue(a.exists() && !b.exists())
        assertTrue(k.exists())
    }

    @Test
    fun bothSlotsWithMarkerOneKeepOne() {
        a.create(AppLockMethod.BIOMETRICS)
        b.create(AppLockMethod.SCREEN_LOCK)
        val k = keys()
        k.recover(1)
        assertTrue(!a.exists() && b.exists())
        assertTrue(k.exists())
    }

    @Test
    fun bothSlotsWithNoMarkerKeepTheFirstAlias() {
        a.create(AppLockMethod.BIOMETRICS)
        b.create(AppLockMethod.SCREEN_LOCK)
        val k = keys()
        k.recover(com.vettid.core.data.prefs.AppPreferences().appLockKeySlot) // never written: 0
        assertTrue(a.exists() && !b.exists())
    }

    @Test
    fun aMarkerThatNamesNoKeyFallsBackToTheOtherSlot() {
        b.create(AppLockMethod.BIOMETRICS)
        val k = keys()
        k.recover(0)
        assertTrue(k.exists())
        assertTrue(b.exists())
    }

    @Test
    fun aCrashAfterThePrefsWriteKeepsTheNewKey() {
        // Old key in slot 0, new one in slot 1, the marker already says 1; the old key was not deleted yet.
        a.create(AppLockMethod.BIOMETRICS)
        b.create(AppLockMethod.SCREEN_LOCK)
        val k = keys()
        k.recover(1)
        assertEquals(AppLockMethod.SCREEN_LOCK, b.method)
        assertFalse(a.exists())
        assertTrue(k.exists())
    }

    @Test
    fun aProcessDeathDuringAMethodChangeKeepsTheLockOnWithTheOldMethod() = runTest {
        val prefs = InMemoryPreferencesRepository()
        var clock = 0L
        fun lock(k: AppLockKeys) = AppLock(prefs, k, MemFile(), now = { clock }, elapsed = { clock })
        val first = lock(keys())
        first.start()
        first.completeEnable(first.cipherToEnable(AppLockMethod.BIOMETRICS))
        first.setTimeout(AppLockTimeout.IMMEDIATELY)
        first.cipherToEnable(AppLockMethod.SCREEN_LOCK) // the prompt is up; then the app is swiped away
        assertTrue(a.exists() && b.exists())
        val second = lock(keys()) // a new process over the same prefs and the same Keystore
        second.start()
        assertEquals(AppLockState.LOCKED, second.state.value)
        assertEquals(AppLockMethod.BIOMETRICS, second.method.value)
        assertTrue(prefs.current.value.appLockEnabled)
        assertTrue("only the current key is left", a.exists() != b.exists())
        assertTrue(second.completeUnlock(second.cipherToUnlock()!!))
    }
}
