package com.vettid.app.calls

import com.vettid.core.data.vault.AppPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 3 (the release gate): the calls count the vault as open only once its release is known to be current. While the
 * release is checked (from the opening on) or the vault is behind, no call starts or rings; one found behind by a read on
 * the return to the foreground closes the vault for the calls, which ends a live call (`CallsController`'s close).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallsReleaseGateTest {
    @Test
    fun openOnlyUnlockedAndNotRequired() {
        assertTrue(callsOpen(AppPhase.Unlocked, releaseRequired = false))
        assertFalse(callsOpen(AppPhase.Unlocked, releaseRequired = true))
        assertFalse(callsOpen(AppPhase.Locked, releaseRequired = false))
    }

    @Test
    fun noCallRingsWhileRequiredAndALiveOneEndsWhenTheVaultIsFoundBehind() = runTest(UnconfinedTestDispatcher()) {
        val phase = MutableStateFlow<AppPhase>(AppPhase.Locked)
        val opens = MutableStateFlow(0)
        val required = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { callsUnlocked(phase, opens, required).collect { seen += it } }
        // An opening: counted and its release check started before the phase shows it.
        opens.value = 1
        required.value = true
        phase.value = AppPhase.Unlocked
        assertEquals("closed while the release is checked", listOf(false), seen)
        required.value = false
        assertEquals(listOf(false, true), seen)
        // A read on the return to the foreground finds a newer release: the vault is closed for the calls.
        required.value = true
        assertEquals(listOf(false, true, false), seen)
        job.cancel()
    }
}
