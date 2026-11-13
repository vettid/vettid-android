package com.vettid.core.wallet

import com.vettid.core.vault.VaultDevice
import com.vettid.core.vault.VaultStateException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * (ANDROID-PLAN §4 "While pending") A `wallet.sign` whose first send the device's lock gate held sent nothing: it is
 * [SignOutcome.NotSent], so the reviewed payment is given back, as for a failure before the request went out. A failure after
 * it went out may have reached the vault, as before.
 */
class WalletSignHeldTest {
    private val held = VaultStateException("wallet.sign: held while a lock is pending", "wallet.sign", VaultDevice.CODE_LOCK_PENDING)

    @Test
    fun aSignHeldByTheLockGateDidNotReachTheVault() {
        assertFalse(VaultWalletOps.mayHaveReached(sent = true, e = held))
        assertFalse(VaultWalletOps.mayHaveReached(sent = true, e = java.io.IOException("wrapped", held)))
    }

    @Test
    fun aFailureAfterTheSendMayHaveReachedItAsBefore() {
        assertTrue(VaultWalletOps.mayHaveReached(sent = true, e = VaultStateException("wallet.sign: no response", "wallet.sign")))
        assertFalse(VaultWalletOps.mayHaveReached(sent = false, e = java.io.IOException("before")))
    }
}
