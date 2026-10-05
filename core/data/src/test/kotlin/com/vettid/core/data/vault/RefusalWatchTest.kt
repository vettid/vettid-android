package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A replaced phone whose vault the new phone already unlocked opens the app and meets only the relay's
 * `token_revoked`: repeated refusals send it to the unlock screen, where the PIN asks the enclave. Never a wipe.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefusalWatchTest {
    private var routed = 0
    private val watch = RefusalWatch { routed++ }

    @Test
    fun repeatedRefusalsSendTheOpenAppToTheUnlockScreenOnce() {
        assertFalse(watch.onRefusals(1, AppPhase.Unlocked))
        assertFalse(watch.onRefusals(2, AppPhase.Unlocked))
        assertEquals(0, routed)
        assertTrue(watch.onRefusals(RefusalWatch.THRESHOLD, AppPhase.Unlocked))
        assertEquals(1, routed)
        assertTrue(watch.suspected.value)
        // More refusals (or the phase already Locked) change nothing.
        assertFalse(watch.onRefusals(4, AppPhase.Locked))
        assertFalse(watch.onRefusals(5, AppPhase.Unlocked))
        assertEquals(1, routed)
    }

    @Test
    fun aWorkingPhoneWithPassingTokenErrorsStaysWhereItIs() {
        // Two refusals, then the vault speaks (the count is back at 0): a revoked token the vault replaced.
        watch.onRefusals(1, AppPhase.Unlocked)
        watch.onRefusals(2, AppPhase.Unlocked)
        watch.onRefusals(0, AppPhase.Unlocked)
        watch.onRefusals(1, AppPhase.Unlocked)
        watch.onRefusals(2, AppPhase.Unlocked)
        assertEquals(0, routed)
        assertFalse(watch.suspected.value)
    }

    @Test
    fun onlyTheOpenAppIsSentBack() {
        for (p in listOf(AppPhase.Locked, AppPhase.Starting, AppPhase.SignedOut, AppPhase.Setup(SetupStage.RECOVERING))) {
            assertFalse("$p", watch.onRefusals(RefusalWatch.THRESHOLD, p))
        }
        assertEquals(0, routed)
        assertFalse(watch.suspected.value)
    }

    @Test
    fun anySignThatTheVaultKnowsThisPhoneClearsTheSuspicion() {
        watch.onRefusals(RefusalWatch.THRESHOLD, AppPhase.Unlocked)
        assertTrue(watch.suspected.value)
        watch.clear() // a sealed unlock result
        assertFalse(watch.suspected.value)
        watch.onRefusals(RefusalWatch.THRESHOLD, AppPhase.Unlocked)
        assertTrue(watch.suspected.value)
        watch.onRefusals(0, AppPhase.Locked) // a message from the vault
        assertFalse(watch.suspected.value)
        assertEquals(2, routed)
    }

    @Test
    fun refusalsNeverWipe() = runTest {
        var wipes = 0
        val holder = HolderWatch(this) { wipes++ }
        // Wired as VaultManager does: the watch knows nothing of the wipe; the failures it stands for are no proof.
        val w = RefusalWatch { }
        for (n in 1..10) w.onRefusals(n, AppPhase.Unlocked)
        assertTrue(w.suspected.value)
        assertFalse(holder.onFailure(VaultFailure(FailureKind.NETWORK, "token_revoked")))
        assertFalse(holder.onFailure(VaultFailure(FailureKind.OTHER, "unreadable_result")))
        advanceUntilIdle()
        assertEquals(0, wipes)
        assertEquals(0, holder.count)
    }
}
