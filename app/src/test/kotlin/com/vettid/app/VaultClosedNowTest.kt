package com.vettid.app

import com.vettid.core.data.vault.AppPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A notification tap not yet taken is dropped only when an open vault closes, never at a cold start. */
class VaultClosedNowTest {
    @Test
    fun onlyAnOpenVaultThatClosesCounts() {
        assertTrue(vaultClosedNow(AppPhase.Unlocked, AppPhase.Locked))
        assertFalse(vaultClosedNow(null, AppPhase.Locked))
        assertFalse(vaultClosedNow(AppPhase.Starting, AppPhase.Locked))
        assertFalse(vaultClosedNow(AppPhase.Locked, AppPhase.Unlocked))
        assertFalse(vaultClosedNow(AppPhase.Unlocked, AppPhase.Unlocked))
        assertFalse(vaultClosedNow(AppPhase.Locked, AppPhase.Locked))
    }
}
