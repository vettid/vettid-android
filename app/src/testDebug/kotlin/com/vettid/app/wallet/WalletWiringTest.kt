package com.vettid.app.wallet

import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.ChainSettingsFile
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletFlagsFile
import com.vettid.core.wallet.WalletWipe
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** WalletModule's rules: when the wallets may be held, and what its wipe hook does (in order, and even if a clear throws). */
class WalletWiringTest {
    private fun check(state: OwnerCheckState) = OwnerCheckView(state, null, 86_400, 0, true, null)

    @Test
    fun theWalletsAreHeldOnlyWhileTheVaultIsUnlockedAndNeitherHeldNorDue() {
        assertTrue(WalletModule.openOf(AppPhase.Unlocked, null))
        assertTrue(WalletModule.openOf(AppPhase.Unlocked, check(OwnerCheckState.OK)))
        assertFalse(WalletModule.openOf(AppPhase.Unlocked, check(OwnerCheckState.DUE)))
        assertFalse(WalletModule.openOf(AppPhase.Unlocked, check(OwnerCheckState.HELD)))
        assertFalse(WalletModule.openOf(AppPhase.Starting, null))
    }

    @Test
    fun theWipeHookMovesTheGenerationThenFencesEvenIfAClearThrows() = runBlocking {
        val wipe = WalletWipe()
        wipe.register { error("a clear fails") }
        val flags = WalletFlags(object : WalletFlagsFile {
            override fun load(): ByteArray? = null

            override fun save(bytes: ByteArray) = Unit
        }) { wipe.generation }
        val servers = ChainServers(object : ChainSettingsFile {
            override fun load(): ByteArray? = null

            override fun save(bytes: ByteArray) = Unit
        }, ChainDefaults(), OkHttpClient()) { wipe.generation }
        val e = runCatching { WalletModule.wipeHook(wipe, flags, servers) }.exceptionOrNull()
        assertEquals("a clear fails", e?.message)
        // The generation moved before the fences ran (they returned: nothing was in progress).
        assertEquals(1L, wipe.generation)
    }
}
