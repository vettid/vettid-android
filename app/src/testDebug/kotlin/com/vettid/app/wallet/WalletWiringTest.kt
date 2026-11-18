package com.vettid.app.wallet

import com.vettid.core.chain.ChainDefaults
import com.vettid.core.chain.ChainServers
import com.vettid.core.chain.ChainSettingsFile
import com.vettid.core.data.vault.AppPhase
import com.vettid.core.data.vault.OwnerCheckState
import com.vettid.core.data.vault.OwnerCheckView
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.vault.VaultOpException
import com.vettid.core.wallet.WalletFlags
import com.vettid.core.wallet.WalletFlagsFile
import com.vettid.core.wallet.WalletWipe
import com.vettid.core.wallet.WalletsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
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
        assertTrue(WalletModule.openOf(AppPhase.Unlocked, null, false))
        assertTrue(WalletModule.openOf(AppPhase.Unlocked, check(OwnerCheckState.OK), false))
        assertFalse(WalletModule.openOf(AppPhase.Unlocked, check(OwnerCheckState.DUE), false))
        assertFalse(WalletModule.openOf(AppPhase.Unlocked, check(OwnerCheckState.HELD), false))
        assertFalse(WalletModule.openOf(AppPhase.Starting, null, false))
    }

    /** Rule 3 (the release gate): while the vault's update is required, or its release is checked, no wallet is held. */
    @Test
    fun noWalletIsHeldWhileTheReleaseUpdateIsRequired() {
        assertFalse(WalletModule.openOf(AppPhase.Unlocked, null, true))
        assertEquals(null, WalletModule.sessionOf(AppPhase.Unlocked, null, 9, true))
        assertEquals(9, WalletModule.sessionOf(AppPhase.Unlocked, null, 9, false))
    }

    /**
     * The drawer's entry is the manager's open state and nothing else (Rule 3: no answer of the vault hides it); a list that cannot be
     * read keeps it, and the list shows the failure with Try again.
     */
    @Test
    fun theDrawerEntryIsShownWhileTheWalletsAreOpen() = runBlocking {
        val flags = WalletFlags(object : WalletFlagsFile {
            override fun load(): ByteArray? = null

            override fun save(bytes: ByteArray) = Unit
        }) { 0L }
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // Every request is answered `unsupported_type`, as an older vault would.
        val m = WalletsManager(scope, { throw VaultOpException("wallet.list", "unsupported_type") }, { error("no chain") }, flags, { true })
        val access = WalletModule.walletAccess(m, scope)
        assertFalse(access.shown.value)
        assertTrue(runCatching { m.open() }.exceptionOrNull() is VaultFailure)
        // The failure leaves the entry shown: the list says it with Try again.
        assertTrue(access.shown.value)
        assertFalse(m.listed.value)
        m.clear()
        assertFalse(access.shown.value)
        scope.cancel()
    }

    @Test
    fun theSessionIsTheOpeningsCountWhileOpenAndNoneWhileNot() {
        assertEquals(7, WalletModule.sessionOf(AppPhase.Unlocked, null, 7, false))
        // The vault was closed and opened again: the same phase is another session.
        assertEquals(8, WalletModule.sessionOf(AppPhase.Unlocked, null, 8, false))
        assertEquals(null, WalletModule.sessionOf(AppPhase.Unlocked, check(OwnerCheckState.HELD), 8, false))
        assertEquals(null, WalletModule.sessionOf(AppPhase.Starting, null, 8, false))
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
