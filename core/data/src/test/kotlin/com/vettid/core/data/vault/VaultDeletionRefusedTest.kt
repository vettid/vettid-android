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
        // The timer still runs past a refused GONE (reads at 5, 15, 45 and 105 s): a GONE taken as gone would stop it at
        // the first timed read.
        advanceTimeBy(5 * min)
        runCurrent()
        assertTrue("the watch kept reading: $reads", reads >= 5)
        assertEquals(reads, asked)
        w.stop()
    }

    // The vault's own word with no "being deleted" up starts it only while this phone still has its vault.
    @Test
    fun theVaultsWordStartsOnlyWithAVault() {
        fun starts(p: AppPhase, id: String = "v", closed: Boolean = false, api: Boolean = false) =
            VaultDeletion.vaultWordStarts(p, id, closed, api)
        assertTrue(starts(AppPhase.Locked))
        assertTrue(starts(AppPhase.Unlocked))
        assertTrue(starts(AppPhase.Starting))
        assertFalse("no vault id", starts(AppPhase.Locked, id = ""))
        assertFalse("after a wipe", starts(AppPhase.SignedOut))
        assertFalse(starts(AppPhase.Setup(SetupStage.NEW_VAULT)))
        assertFalse("already up", starts(AppPhase.Deleting()))
        assertFalse("a wipe runs (still Locked)", starts(AppPhase.Locked, closed = true))
        assertFalse("an erase that failed", starts(AppPhase.Unreachable(FailureKind.ERASE_FAILED), closed = true))
        assertFalse("a 410-only marker stands", starts(AppPhase.Locked, api = true))
    }
}
