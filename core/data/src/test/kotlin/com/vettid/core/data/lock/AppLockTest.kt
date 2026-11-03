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

/** A software stand-in for the biometric-gated Keystore key. */
internal class SoftKeys : AppLockKeys {
    var key: SecretKey? = null
    val created = mutableListOf<AppLockMethod>()

    override fun create(method: AppLockMethod) {
        created += method
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun exists() = key != null

    override fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun delete() {
        key = null
    }
}

/** The earlier builds' wrapped app-data key file: only its deletion is left. */
internal class MemFile : WrappedKeyFile {
    var b: ByteArray? = byteArrayOf(1, 2, 3)

    override fun delete() {
        b = null
    }
}

class AppLockTest {
    private var clock = 0L // the monotonic clock
    private var wallSkew = 0L // the date set by hand: the wall clock is the monotonic one plus this
    private val prefs = InMemoryPreferencesRepository()
    private val keys = SoftKeys()
    private val file = MemFile()
    private fun lock() = AppLock(prefs, keys, file, now = { clock + wallSkew }, elapsed = { clock })

    @Test
    fun offByDefault() = runTest {
        val l = lock()
        l.start()
        assertEquals(AppLockState.DISABLED, l.state.value)
    }

    @Test
    fun enableThenRestartStartsLockedAndUnlocks() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        assertTrue(prefs.current.value.appLockEnabled)

        val again = lock()
        again.start()
        assertEquals(AppLockState.LOCKED, again.state.value)
        assertTrue(again.completeUnlock(again.cipherToUnlock()!!))
        assertEquals(AppLockState.UNLOCKED, again.state.value)
    }

    /** A cipher that did not come from an authentication does not unlock (here: a cipher that cannot encrypt). */
    @Test
    fun aCipherThatDoesNotWorkDoesNotUnlock() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.lockNow()
        val broken = Cipher.getInstance("AES/GCM/NoPadding") // never initialised
        assertFalse(l.completeUnlock(broken))
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    /** 2026-10-27 audit: the random data key nothing used is gone; the stored wrapped key is deleted at the next start. */
    @Test
    fun theWrappedKeyEarlierBuildsStoredIsDeletedAtStart() = runTest {
        assertNotNull(file.b)
        lock().start()
        assertNull(file.b)
    }

    /** A6 audit P4: an enabled lock whose key is gone leaves "pending": off. */
    @Test
    fun anEnabledLockWithoutItsKeyTurnsOffAtStart() = runTest {
        prefs.setAppLockEnabled(true)
        val l = lock()
        l.start()
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(prefs.current.value.appLockEnabled)
    }

    @Test
    fun aPreferenceThatCannotBeWrittenAtStartDoesNotLeaveTheLockPending() = runTest {
        val readOnly = object : com.vettid.core.data.prefs.PreferencesRepository by prefs {
            override suspend fun setAppLockEnabled(enabled: Boolean) = throw java.io.IOException("read-only")
        }
        prefs.setAppLockEnabled(true) // on, but its key is gone: start turns it off and tries to write that down
        val l = AppLock(readOnly, keys, file, now = { clock + wallSkew }, elapsed = { clock })
        l.start()
        assertEquals(AppLockState.DISABLED, l.state.value)
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
    fun theMonotonicTimeAtTheTimeoutLocksAndOneMillisecondBeforeDoesNot() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.ONE_MINUTE)
        l.onBackground()
        clock += 60_000 - 1
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        l.onBackground()
        clock += 60_000
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aDateSetBackInTheBackgroundLocksOnReturn() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.ONE_HOUR)
        l.onBackground()
        clock += 10_000
        wallSkew = -24 * 3_600_000L // a day back: wall now is earlier than at the background
        assertTrue(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aSmallCorrectionBackwardsIsNotADateSetBack() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.ONE_MINUTE)
        l.onBackground()
        clock += 10_000
        wallSkew = -5_000 // a step of exactly the tolerance: still a correction
        assertFalse(l.wouldShowLock())
        wallSkew = -5_001
        assertTrue(l.wouldShowLock())
    }

    @Test
    fun aDateSetBackToAnEarlierTimeThanTheMonotonicOneLocksEvenAfterTheBackgroundTime() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.ONE_HOUR)
        val min = 60_000L
        clock = 12 * 60 * min // 12:00 (the wall clock is the same)
        l.onBackground()
        clock += 30 * min // 12:30
        wallSkew = -20 * min // the date reads 12:10: after the background time, but 20 minutes short
        assertTrue(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aDateSetForwardDoesNotLockByItself() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.ONE_MINUTE)
        l.onBackground()
        clock += 10_000
        wallSkew = 30L * 24 * 3_600_000 // a month forward: the monotonic time decides
        assertFalse(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun aNewProcessStartsLockedWhateverTheClocks() = runTest {
        prefs.setAppLockEnabled(true)
        val l = AppLock(prefs, keys.also { it.create(AppLockMethod.BIOMETRICS) }, file, now = { 5L }, elapsed = { 3L })
        assertTrue("before start: covered", l.wouldShowLock())
        l.start()
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertTrue(l.wouldShowLock())
    }

    /** A6 audit R3: a rotation, fold or theme change recreates the activity; with "Immediately" that locked the app. */
    @Test
    fun aConfigurationChangeDoesNotCountAsLeavingTheApp() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.IMMEDIATELY)
        l.onBackground(changingConfigurations = true)
        clock += 5_000
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        // Really leaving still locks.
        l.onBackground(changingConfigurations = false)
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    private suspend fun unlockedWith(t: AppLockTimeout): AppLock = lock().also {
        it.start()
        it.completeEnable(it.cipherToEnable())
        it.setTimeout(t)
    }

    /** Was "promptsDoNotCountAsLeavingTheApp" (no mark while authenticating); now the mark is kept and a success clears it. */
    @Test
    fun aPromptThatSucceedsAfterABackgroundAndReturnDoesNotLock() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.promptStarted()
        l.onBackground()
        clock += 5_000
        l.onForeground() // left alone while the prompt is up
        l.promptEnded(PromptOutcome.SUCCEEDED, inForeground = true)
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        assertFalse(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun homeDuringAPresencePromptStillTimesOut() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.promptStarted()
        l.onBackground()
        l.promptEnded(PromptOutcome.SYSTEM_CANCELED, inForeground = false)
        assertFalse(l.wouldShowLock())
        clock += 60_000
        assertTrue(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun theCallbackBeforeTheStopStillTimesOut() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.promptStarted()
        l.promptEnded(PromptOutcome.SYSTEM_CANCELED, inForeground = true)
        l.onBackground()
        clock += 60_000
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aPromptStillUpOnReturnAfterTheTimeoutIsDecidedByItsEnd() = runTest {
        val cases = listOf(PromptOutcome.DISMISSED to true, PromptOutcome.SYSTEM_CANCELED to true, PromptOutcome.SUCCEEDED to false)
        for ((outcome, locked) in cases) {
            val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
            l.promptStarted()
            l.onBackground()
            clock += 120_000
            l.onForeground()
            assertEquals(AppLockState.UNLOCKED, l.state.value) // not cleared, not locked yet
            l.promptEnded(outcome, inForeground = true)
            assertEquals(outcome.name, if (locked) AppLockState.LOCKED else AppLockState.UNLOCKED, l.state.value)
            if (!locked) assertFalse(l.wouldShowLock())
            clock += 120_000
            l.onForeground() // the mark is gone either way
            assertEquals(if (locked) AppLockState.LOCKED else AppLockState.UNLOCKED, l.state.value)
        }
    }

    @Test
    fun aDeviceCredentialUnlockWithImmediatelyDoesNotLockOnTheLateStart() = runTest {
        for ((wait, locked) in listOf(3_000L to false, 3_001L to true)) {
            val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
            l.lockNow()
            l.promptStarted()
            l.onBackground()
            l.promptEnded(PromptOutcome.SUCCEEDED, inForeground = false)
            assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
            clock += wait
            l.onForeground()
            assertEquals("$wait", if (locked) AppLockState.LOCKED else AppLockState.UNLOCKED, l.state.value)
        }
    }

    @Test
    fun theGraceIsEndedByANewBackground() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.promptStarted()
        l.promptEnded(PromptOutcome.SUCCEEDED, inForeground = false)
        l.onBackground() // a real stop after the callback: counted from here
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aDismissedPromptInTheBackgroundWithNoMarkRecordsOne() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.promptStarted()
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = false)
        clock += 60_000
        assertTrue(l.wouldShowLock())
    }

    @Test
    fun aRotationDuringAPromptKeepsTheMarkAndAddsNone() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.promptStarted()
        l.abandonPrompt()
        assertFalse(l.authenticating)
        clock += 60_000
        assertFalse("no mark added", l.wouldShowLock())
        val m = unlockedWith(AppLockTimeout.ONE_MINUTE)
        m.onBackground()
        m.promptStarted()
        clock += 30_000
        m.abandonPrompt()
        clock += 30_000
        assertTrue("the earlier mark kept", m.wouldShowLock())
    }

    @Test
    fun aLateCallbackAfterARotationLeavesNoMark() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.promptStarted()
        l.abandonPrompt()
        // MainActivity skips promptEnded once destroyed; nothing the AppLock saw recorded a mark.
        clock += 60_000
        assertFalse(l.wouldShowLock())
        l.onForeground()
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun cancellingAMethodChangeInTheForegroundDoesNotLock() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        l.promptStarted()
        l.onBackground() // the credential screen stopped the activity
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = true)
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        assertFalse(l.wouldShowLock())
        l.enableCancelled()
        assertFalse(l.invalidated.value)
        // Out of the foreground it is unchanged: the mark stays.
        val m = unlockedWith(AppLockTimeout.IMMEDIATELY)
        m.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        m.promptStarted()
        m.promptEnded(PromptOutcome.DISMISSED, inForeground = false)
        assertTrue(m.wouldShowLock())
    }

    @Test
    fun resetClearsThePromptState() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.promptStarted()
        l.promptEnded(PromptOutcome.SUCCEEDED, inForeground = false)
        l.promptStarted()
        l.reset()
        assertFalse(l.authenticating)
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.setTimeout(AppLockTimeout.IMMEDIATELY)
        l.onForeground() // no mark, no grace left over
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        l.onBackground()
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun invalidatedKeyTurnsTheLockOff() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable())
        l.lockNow()
        keys.delete() // the key is gone (or was invalidated)
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
        // As an older build left it: on, a key, no method stored.
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
        keys.delete() // the key no longer opens (the screen lock was removed)
        assertNull(l.cipherToUnlock())
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(l.invalidated.value)
    }
}
