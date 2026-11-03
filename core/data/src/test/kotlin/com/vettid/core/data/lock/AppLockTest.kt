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
    var pendingKey: SecretKey? = null
    private var slot = 0
    val created = mutableListOf<AppLockMethod>()

    var failCreate = false

    override fun createPending(method: AppLockMethod) {
        check(!failCreate) { "no screen lock" }
        created += method
        pendingKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }

    override fun pendingCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, pendingKey) }

    override fun promote() {
        slot = 1 - slot
        key = pendingKey
        pendingKey = null
    }

    override fun discardPending() {
        pendingKey = null
    }

    override fun recover(markedSlot: Int) = Unit

    override fun pendingSlot() = 1 - slot

    override fun exists() = key != null

    override fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

    override fun delete() {
        key = null
        pendingKey = null
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
        val l = AppLock(prefs, keys.also { it.createPending(AppLockMethod.BIOMETRICS); it.promote() }, file, now = { 5L }, elapsed = { 3L })
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
            if (locked) {
                // No lock-and-prompt loop: the lock after a successful prompt waits for the member's "Unlock".
                assertFalse("no automatic prompt", l.prompts.tryStart(auto = true, owner = this))
                assertTrue("Unlock still works", l.prompts.tryStart(auto = false, owner = this))
            }
        }
    }

    @Test
    fun aPresenceCheckThatSucceedsWhileAwayStillLocksOnTheRealReturn() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.promptStarted()
        l.onBackground() // the member swipes home as the check succeeds
        l.promptEnded(PromptOutcome.SUCCEEDED, inForeground = false)
        assertEquals("nothing decided while away", AppLockState.UNLOCKED, l.state.value)
        clock += 3_600_000
        assertTrue("covered while away", l.wouldShowLock())
        l.onForeground() // the real return, an hour later
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun theHeldPromptIsReleasedByTheNextUnlock() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.lockNow()
        l.promptStarted()
        l.onBackground()
        l.promptEnded(PromptOutcome.SUCCEEDED, inForeground = false)
        assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
        clock += 3_001
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertFalse("held", l.prompts.tryStart(auto = true, owner = this))
        // The member's Unlock (the held prompt was not started automatically: this one is theirs).
        assertTrue(l.prompts.tryStart(auto = false, owner = this))
        assertTrue(l.prompts.finished(this, PromptOutcome.SUCCEEDED))
        assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
        l.onBackground()
        l.onForeground() // a later, unrelated timeout lock
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertTrue("prompts automatically again", l.prompts.tryStart(auto = true, owner = this))
    }

    @Test
    fun aTimeoutLockStillPromptsAutomatically() = runTest {
        val l = unlockedWith(AppLockTimeout.IMMEDIATELY)
        l.onBackground()
        l.onForeground()
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertTrue(l.prompts.tryStart(auto = true, owner = this))
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
    fun cancellingAMethodChangeKeepsTheLockOnWithTheOldMethodAndKey() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable(AppLockMethod.BIOMETRICS))
        l.setTimeout(AppLockTimeout.IMMEDIATELY)
        val old = keys.key
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        l.promptStarted(enable = true)
        l.onBackground() // the credential screen stopped the activity
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = true)
        // The return decision: "Immediately" locks, with no notice and no change of method or key.
        assertEquals(AppLockState.LOCKED, l.state.value)
        assertFalse(l.invalidated.value)
        assertEquals(AppLockMethod.BIOMETRICS, l.method.value)
        assertTrue(prefs.current.value.appLockEnabled)
        assertTrue(old === keys.key)
        assertNull(keys.pendingKey)
        assertTrue(l.completeUnlock(l.cipherToUnlock()!!))
    }

    @Test
    fun aCancelledFirstEnableLeavesNoKeyAndTheLockOff() = runTest {
        val l = lock()
        l.start()
        l.cipherToEnable(AppLockMethod.BIOMETRICS)
        l.promptStarted(enable = true)
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = true)
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(keys.exists())
        assertNull(keys.pendingKey)
        assertFalse(prefs.current.value.appLockEnabled)
    }

    @Test
    fun aLateSuccessForADroppedEnableChangesNothingAndDoesNotThrow() = runTest {
        val l = lock()
        l.start()
        val c = l.cipherToEnable(AppLockMethod.BIOMETRICS)
        l.enableCancelled() // dropped (cancelled, abandoned or destroyed) before the success arrives
        l.completeEnable(c)
        assertEquals(AppLockState.DISABLED, l.state.value)
        assertFalse(prefs.current.value.appLockEnabled)
        assertNull(keys.pendingKey)
    }

    @Test
    fun aSuccessfulMethodChangeSwapsTheKey() = runTest {
        val l = lock()
        l.start()
        l.completeEnable(l.cipherToEnable(AppLockMethod.BIOMETRICS))
        val old = keys.key
        val c = l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        assertTrue("the old key is in place until the proof", old === keys.key)
        l.completeEnable(c)
        assertFalse(old === keys.key)
        assertNull(keys.pendingKey)
        assertEquals(AppLockMethod.SCREEN_LOCK, l.method.value)
    }

    @Test
    fun aMethodChangeAbandonedByARotationKeepsTheOldKey() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        val old = keys.key
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        l.promptStarted(enable = true)
        l.abandonPrompt()
        assertTrue(old === keys.key)
        assertNull(keys.pendingKey)
        assertEquals(AppLockState.UNLOCKED, l.state.value)
        l.enableCancelled() // nothing left in progress
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun aProofThatFailsDropsThePendingKey() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        val old = keys.key
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        keys.pendingKey = null // the key is gone: the cipher cannot prove
        runCatching { l.completeEnable(javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")) }
        assertTrue(old === keys.key)
        assertEquals(AppLockState.UNLOCKED, l.state.value)
    }

    @Test
    fun anAbandonedEnablePromptDoesNotMakeALaterPresenceCancelSkipTheLock() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        l.promptStarted(enable = true)
        l.abandonPrompt() // a rotation: the cancel callback never comes
        l.promptStarted()
        l.onBackground()
        clock += 120_000
        l.promptEnded(PromptOutcome.DISMISSED, inForeground = true)
        assertEquals(AppLockState.LOCKED, l.state.value)
    }

    @Test
    fun aFailedCipherToEnableLeavesNoEnableInProgress() = runTest {
        val l = unlockedWith(AppLockTimeout.ONE_MINUTE)
        l.cipherToEnable(AppLockMethod.SCREEN_LOCK)
        keys.failCreate = true
        try {
            l.cipherToEnable(AppLockMethod.BIOMETRICS)
        } catch (_: IllegalStateException) {
        }
        keys.failCreate = false
        l.enableCancelled() // nothing in progress: must not disable the lock
        assertEquals(AppLockState.UNLOCKED, l.state.value)
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
        assertEquals(AppPreferences(appLockMethod = AppLockMethod.BIOMETRICS, appLockKeySlot = 1), prefs.current.value)
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
