package com.vettid.app.calls

import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.SetupStage
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the calls learn that the phone left its vault (CallsModule): never at a lock, once per sign-out or new setup. */
class VaultLeavingsTest {
    private suspend fun leavings(vararg phases: AppPhase): Int = vaultLeavings(flowOf(*phases)).toList().size

    @Test
    fun aLockAndAnUnlockOfTheSameVaultAreNoLeaving() = runTest {
        assertEquals(
            0,
            leavings(
                AppPhase.Starting, AppPhase.Locked, AppPhase.Unlocked, AppPhase.Locked, AppPhase.Unlocked,
                AppPhase.Unreachable(FailureKind.NETWORK), AppPhase.Locked, AppPhase.Deleting(), AppPhase.Locked,
            ),
        )
    }

    @Test
    fun aSignOutOrANewSetupIsOneLeavingEach() = runTest {
        // Signed out (a sign-out, a wipe, a deletion's end), then another vault set up: one leaving.
        assertEquals(
            1,
            leavings(
                AppPhase.Unlocked, AppPhase.Locked, AppPhase.SignedOut, AppPhase.Setup(SetupStage.NEW_VAULT),
                AppPhase.Setup(SetupStage.NEEDS_CREDENTIAL), AppPhase.Locked, AppPhase.Unlocked,
            ),
        )
        // Each time again, and a setup begun straight from an open vault's phase counts too.
        assertEquals(2, leavings(AppPhase.Unlocked, AppPhase.SignedOut, AppPhase.Unlocked, AppPhase.Setup(SetupStage.NEW_VAULT)))
    }
}
