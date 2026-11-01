// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.Network
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * W1d-2: `raiseCaps` over a pending `wallet.caps.set`, the used amount of the period, and "Reset wallet settings on this phone"
 * (an unreadable flags file replaced by a fresh one; a limit the member may have chosen is asked again, never quietly dropped).
 */
class WalletCapsScreenTest {
    private val ops = FakeWalletOps()
    private val chain = FakeChain()
    private val flagsFile = object : WalletFlagsFile {
        var bytes: ByteArray? = null
        var saves = 0

        override fun load(): ByteArray? = bytes

        override fun save(bytes: ByteArray) {
            saves++
            this.bytes = bytes
        }
    }
    private val caps = WalletCaps(500_000, 2_000_000)
    private val unreadable = """{"version":2,"backed_up":["01WALLET000000000000000001"]}"""

    private fun manager() = WalletsManager(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
        { ops },
        { n -> chain.open(n) },
        WalletFlags(flagsFile),
        { it == Network.SIGNET || it == Network.TESTNET4 },
        { Instant.parse("2026-10-31T20:00:00Z") },
    )

    private fun wallet(id: String, cappedAlready: Boolean = false, caps: String? = WalletFixtures.DEFAULT_CAPS) =
        WalletFixtures.wallet(id = id, caps = caps).let { w ->
            if (cappedAlready) w.copy(capped = true, perTxSats = 500_000, perPeriodSats = 2_000_000) else w
        }.also { ops.wallets[id] = it }

    // The period's total

    @Test
    fun theUsedAmountAndThePeriodComeFromTheWallet() {
        val w = WalletFixtures.wallet(caps = """{"per_tx_sats":500000,"per_period_sats":2000000,"period_seconds":86400,"spent_sats":750000}""")
        assertEquals(750_000L, w.spentSats)
        assertEquals(86_400L, w.periodSeconds)
        assertEquals(500_000L, w.perTxSats)
        val none = WalletFixtures.wallet()
        assertEquals(0L, none.spentSats)
        assertNull(none.perPeriodSats)
        assertNull(WalletFixtures.wallet(caps = null).spentSats)
    }

    // A raise over a pending set

    @Test
    fun aRaiseSendsThePendingSetFirstAndIsThenAskedAgainNeverRaisingOverALowerThatHasNotArrived() = runBlocking {
        val w = wallet("01WALLET000000000000000001")
        WalletFlags(flagsFile).setPendingCaps(w.id, WalletCaps(100_000, null))
        // The set cannot be sent when the vault opens: it stays pending.
        ops.capsSetFails = "internal"
        val m = manager()
        m.open()
        assertTrue(m.isCapsPending(w.id))
        ops.calls.clear()
        // The set fails again: the raise is not sent at all.
        val e = runCatching { m.raiseCaps(w.id, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals("internal", e.code)
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
        assertNull(ops.wallets.getValue(w.id).perTxSats)
        assertTrue(m.isCapsPending(w.id))
        // The set goes through: it is sent, and the raise, chosen against the limits before it, is not sent with it.
        ops.capsSetFails = null
        ops.calls.clear()
        val settled = runCatching { m.raiseCaps(w.id, 100_000, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.CAPS_SETTLED, settled.code)
        assertEquals(listOf("caps.set:100000:null"), ops.calls.filter { it.startsWith("caps.") })
        assertFalse(m.isCapsPending(w.id))
        assertNull(WalletFlags(flagsFile).pendingCaps(w.id))
        assertEquals(100_000L, m.wallets.value.single().perTxSats)
        // Asked again against the limits as they now are, it is raised.
        m.raiseCaps(w.id, 100_000, 3_000_000, "123456", "pw")
        assertEquals("caps.raise:100000:3000000", ops.calls.last())
        assertEquals(3_000_000L, m.wallets.value.single().perPeriodSats)
    }

    @Test
    fun aRaiseRefusesWhenThePendingLimitsCannotBeKnown() = runBlocking {
        val w = wallet("01WALLET000000000000000001", cappedAlready = true)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertTrue(WalletsManager.UNKNOWN in m.capsPending.value)
        ops.calls.clear()
        val e = runCatching { m.raiseCaps(w.id, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.CAPS_PENDING, e.code)
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertEquals(500_000L, ops.wallets.getValue(w.id).perTxSats)
    }

    // Reset wallet settings on this phone

    @Test
    fun theResetIsOnlyForAnUnreadableFile() = runBlocking {
        wallet("01WALLET000000000000000001")
        val f = WalletFlags(flagsFile)
        f.setBackedUp("01WALLET000000000000000001")
        val before = flagsFile.bytes!!.decodeToString()
        val saves = flagsFile.saves
        val m = manager()
        m.open()
        assertFalse(m.resetSettings())
        assertEquals(saves, flagsFile.saves)
        assertEquals(before, flagsFile.bytes!!.decodeToString())
        assertTrue(f.backedUp("01WALLET000000000000000001"))
    }

    @Test
    fun theResetWritesAFreshFileForgetsTheFlagsAndSendsNothing() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertTrue(WalletsManager.UNKNOWN in m.capsPending.value)
        ops.calls.clear()
        assertTrue(m.resetSettings())
        // Nothing went to the vault.
        assertTrue(ops.calls.isEmpty())
        // A fresh file that reads: nothing backed up, no pending limits, no more unreadable.
        val fresh = WalletFlags(flagsFile)
        assertFalse(fresh.backedUp(id))
        assertEquals(emptyMap<String, WalletCaps>(), fresh.allPendingCaps())
        assertTrue(m.capsPending.value.isEmpty())
        assertEquals(setOf(id), fresh.askCaps())
        assertFalse(m.resetSettings())
    }

    @Test
    fun afterTheResetAnUncappedWalletAsksForALimitBeforeReceiveAndACappedOneGoesOn() = runBlocking {
        val bare = "01WALLET000000000000000001"
        val capped = "01WALLET000000000000000002"
        val old = "01WALLET000000000000000003"
        wallet(bare)
        wallet(capped, cappedAlready = true)
        wallet(old, caps = null)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertEquals(ReceiveResult.SettingsUnreadable, m.receiveAddress(bare))
        assertTrue(m.resetSettings())
        ops.calls.clear()
        assertEquals(ReceiveResult.LimitsAsk, m.receiveAddress(bare))
        assertTrue(m.asksLimits(bare))
        assertTrue(m.receiveAddress(capped) is ReceiveResult.Address)
        assertTrue(m.receiveAddress(old) is ReceiveResult.Address)
        assertFalse(m.asksLimits(capped))
        assertFalse(m.asksLimits(old))
        // The question is asked again, and no address was issued for the wallet that has to answer it.
        assertEquals(ReceiveResult.LimitsAsk, m.receiveAddress(bare))
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertEquals(setOf(bare), WalletFlags(flagsFile).askCaps())
        // A restart does not forget the question.
        val again = manager()
        again.open()
        assertEquals(ReceiveResult.LimitsAsk, again.receiveAddress(bare))
    }

    @Test
    fun noLimitAnswersTheQuestionAndSendsNothing() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        m.resetSettings()
        ops.calls.clear()
        assertTrue(m.answerLimits(id, null))
        assertTrue(ops.calls.none { it.startsWith("caps.") })
        assertFalse(m.asksLimits(id))
        assertTrue(WalletFlags(flagsFile).askCaps()!!.isEmpty())
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
    }

    @Test
    fun aLimitAnswersTheQuestionThroughThePendingPath() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        m.resetSettings()
        ops.calls.clear()
        assertTrue(m.answerLimits(id, caps))
        assertEquals(listOf("caps.set:500000:2000000"), ops.calls.filter { it.startsWith("caps.") })
        assertNull(WalletFlags(flagsFile).pendingCaps(id))
        assertTrue(m.wallets.value.single().capped)
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
    }

    @Test
    fun aLimitThatCannotBeSentStaysPendingAndHoldsReceive() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        m.resetSettings()
        ops.capsSetFails = "internal"
        val e = runCatching { m.answerLimits(id, caps) }.exceptionOrNull()
        assertTrue(e is VaultFailure)
        // The answer is kept as the member's chosen limit: pending, no Receive, and not asked a second time.
        assertEquals(caps, WalletFlags(flagsFile).pendingCaps(id))
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(id))
    }

    @Test
    fun theResetFailsWhenTheFreshFileCannotBeWritten() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        val failing = object : WalletFlagsFile {
            override fun load(): ByteArray = unreadable.encodeToByteArray()

            override fun save(bytes: ByteArray) = throw java.io.IOException("not now")
        }
        val m = WalletsManager(
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
            { ops },
            { n -> chain.open(n) },
            WalletFlags(failing),
            { it == Network.SIGNET || it == Network.TESTNET4 },
        )
        m.open()
        assertFalse(m.resetSettings())
        assertTrue(WalletsManager.UNKNOWN in m.capsPending.value)
    }
}
