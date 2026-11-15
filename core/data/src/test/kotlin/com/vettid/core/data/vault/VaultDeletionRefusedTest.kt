package com.vettid.core.data.vault

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refused "gone", and the vault's own word arriving with no "being deleted" up. */
@OptIn(ExperimentalCoroutinesApi::class)
class VaultDeletionRefusedTest {
    private val min = 60_000L

    // A "gone" the app refused (a 410-only marker came meanwhile, or the attempt ended) is not reported as GONE: the watch
    // keeps its timer and "Check again" concludes nothing.
    @Test
    fun aRefusedGoneIsNotGone() = runTest {
        var reads = 0
        var asked = 0
        val w = DeletionWatch(
            backgroundScope, { currentTime }, read = { reads++; DeletionCheck.GONE }, onGone = { asked++; false }, onSlow = {},
        )
        w.start(DeletingMarker("v", 0, 10 * min), readNow = false)
        assertEquals(DeletionCheck.UNCONFIRMED, w.check())
        assertEquals(1, asked)
        // The timer still runs: the next timed read asks again.
        advanceTimeBy(5 * min)
        runCurrent()
        assertTrue("the watch kept reading", reads > 1)
        assertTrue(asked > 1)
        w.stop()
    }

    // The vault's own word with no "being deleted" up starts it only while this phone still has its vault.
    @Test
    fun theVaultsWordStartsOnlyWithAVault() {
        assertTrue(VaultDeletion.vaultWordStarts(AppPhase.Locked, "v"))
        assertTrue(VaultDeletion.vaultWordStarts(AppPhase.Unlocked, "v"))
        assertTrue(VaultDeletion.vaultWordStarts(AppPhase.Starting, "v"))
        assertFalse("no vault id", VaultDeletion.vaultWordStarts(AppPhase.Locked, ""))
        assertFalse("after a wipe", VaultDeletion.vaultWordStarts(AppPhase.SignedOut, "v"))
        assertFalse(VaultDeletion.vaultWordStarts(AppPhase.Setup(SetupStage.NEW_VAULT), "v"))
        assertFalse("already up", VaultDeletion.vaultWordStarts(AppPhase.Deleting(), "v"))
    }
}
