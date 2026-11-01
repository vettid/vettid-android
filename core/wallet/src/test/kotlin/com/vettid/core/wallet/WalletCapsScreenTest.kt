// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.core.wallet

import com.vettid.core.chain.Network
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
        val e = runCatching { m.raiseCaps(w.id, m.capsSeen(w.id)!!, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals("internal", e.code)
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
        assertNull(ops.wallets.getValue(w.id).perTxSats)
        assertTrue(m.isCapsPending(w.id))
        // The set goes through: it is sent, and the raise, chosen against the limits before it, is not sent with it.
        ops.capsSetFails = null
        ops.calls.clear()
        val seenNow = m.capsSeen(w.id)!!
        val settled = runCatching { m.raiseCaps(w.id, seenNow, 100_000, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.CAPS_SETTLED, settled.code)
        assertEquals(listOf("caps.set:100000:null"), ops.calls.filter { it.startsWith("caps.") })
        assertFalse(m.isCapsPending(w.id))
        assertNull(WalletFlags(flagsFile).pendingCaps(w.id))
        assertEquals(100_000L, m.wallets.value.single().perTxSats)
        // Asked again against the limits as they now are, it is raised.
        m.raiseCaps(w.id, m.capsSeen(w.id)!!, 100_000, 3_000_000, "123456", "pw")
        assertEquals("caps.raise:100000:3000000", ops.calls.last())
        assertEquals(3_000_000L, m.wallets.value.single().perPeriodSats)
    }

    /** (#248 review) A raise chosen against limits that moved since (another device) sends nothing: the PIN and password stay here. */
    @Test
    fun aRaiseChosenAgainstLimitsThatMovedSendsNothing() = runBlocking {
        val w = wallet("01WALLET000000000000000003", cappedAlready = true)
        val m = manager()
        m.open()
        val seen = m.capsSeen(w.id)!!
        // Another device lowers the per-payment limit; this phone reads the wallet again before the member confirms.
        ops.wallets[w.id] = ops.wallets.getValue(w.id).copy(version = w.version + 1, perTxSats = 100_000)
        m.refresh()
        ops.calls.clear()
        val e = runCatching { m.raiseCaps(w.id, seen, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(FailureKind.CONFLICT, e.kind)
        assertEquals(WalletsManager.CAPS_CHANGED, e.code)
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
        assertEquals(100_000L, m.wallets.value.single().perTxSats)
        // A version moved alone refuses too.
        val now = m.capsSeen(w.id)!!
        ops.wallets[w.id] = ops.wallets.getValue(w.id).copy(version = w.version + 2)
        m.refresh()
        val v = runCatching { m.raiseCaps(w.id, now, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.CAPS_CHANGED, v.code)
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
    }

    @Test
    fun aRaiseRefusesWhenThePendingLimitsCannotBeKnown() = runBlocking {
        val w = wallet("01WALLET000000000000000001", cappedAlready = true)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertTrue(WalletsManager.UNKNOWN in m.capsPending.value)
        ops.calls.clear()
        val e = runCatching { m.raiseCaps(w.id, m.capsSeen(w.id)!!, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.SETTINGS_UNREADABLE, e.code)
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
        assertEquals(true, fresh.asksCaps(id))
        assertEquals(false, fresh.asksCaps("01ANYTHING"))
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
        assertEquals(true, WalletFlags(flagsFile).asksCaps(bare))
        assertEquals(true, WalletFlags(flagsFile).asksCaps(capped))
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
        assertEquals(false, WalletFlags(flagsFile).asksCaps(id))
        assertEquals(false, WalletFlags(flagsFile).asksCaps("01OTHER"))
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

    @Test
    fun aResetBeforeTheListIsReadStillAsksEveryUncappedWalletAtReceive() = runBlocking {
        val id = "01WALLET000000000000000001"
        flagsFile.bytes = unreadable.encodeToByteArray()
        // The vault is open but its list has not been read: no wallet is known yet.
        ops.listGate = kotlinx.coroutines.CompletableDeferred()
        val m = manager()
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        kotlinx.coroutines.delay(300)
        assertTrue(m.wallets.value.isEmpty())
        assertTrue(m.resetSettings())
        wallet(id)
        ops.listGate!!.complete(Unit)
        opening.join()
        assertEquals(true, WalletFlags(flagsFile).asksCaps(id))
        assertEquals(ReceiveResult.LimitsAsk, m.receiveAddress(id))
    }

    @Test
    fun limitsChosenInThisSessionSurviveTheReset() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        val m = manager()
        m.open()
        // Chosen now, not sent (the vault refuses), so pending in memory; then the file becomes unreadable and is reset.
        ops.capsSetFails = "internal"
        runCatching { m.create("New", Network.TESTNET4, "pw", caps) }
        val pendingId = m.capsPending.value.single()
        flagsFile.bytes = unreadable.encodeToByteArray()
        assertTrue(m.resetSettings())
        assertEquals(caps, WalletFlags(flagsFile).pendingCaps(pendingId))
        assertTrue(m.isCapsPending(pendingId))
        assertEquals(ReceiveResult.CapsPending, m.receiveAddress(pendingId))
    }

    @Test
    fun aWalletMadeAfterTheResetOrLaterListedIsNotAsked() = runBlocking {
        val old = "01WALLET0000000000000000AA"
        wallet(old)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertTrue(m.resetSettings())
        assertEquals(true, WalletFlags(flagsFile).asksCaps(old))
        // Made here with No limit: not asked, and its flag says so.
        val made = m.create("New", Network.TESTNET4, "pw", null).wallet
        assertEquals(false, WalletFlags(flagsFile).asksCaps(made.id))
        assertTrue(m.receiveAddress(made.id) !is ReceiveResult.LimitsAsk)
        // Made elsewhere after the reset: the list read later does not ask it either.
        val elsewhere = "01WALLET000000000000000009"
        wallet(elsewhere)
        m.refresh()
        assertEquals(false, WalletFlags(flagsFile).asksCaps(elsewhere))
        assertEquals(ReceiveResult.LimitsAsk, m.receiveAddress(old))
    }

    @Test
    fun aResetBeforeTheListIsReadAsksOnlyTheWalletsOfTheFirstListNotOnesMadeMeanwhile() = runBlocking {
        val old = "01WALLET0000000000000000AA"
        wallet(old)
        flagsFile.bytes = unreadable.encodeToByteArray()
        ops.listGate = kotlinx.coroutines.CompletableDeferred()
        val m = manager()
        val opening = CoroutineScope(Dispatchers.Default).launch { m.open() }
        kotlinx.coroutines.delay(300)
        assertTrue(m.resetSettings())
        // A wallet made before the first list read.
        val made = m.create("New", Network.TESTNET4, "pw", null).wallet
        ops.listGate!!.complete(Unit)
        opening.join()
        assertEquals(true, WalletFlags(flagsFile).asksCaps(old))
        assertEquals(false, WalletFlags(flagsFile).asksCaps(made.id))
        assertFalse(m.asksLimits(made.id))
    }

    @Test
    fun aWalletWhoseLimitsWereSetAndThenRemovedHereIsNotAskedAfterwards() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertTrue(m.resetSettings())
        m.lowerCaps(id, 100_000, null)
        m.raiseCaps(id, m.capsSeen(id)!!, null, null, "123456", "pw")
        assertFalse(m.wallets.value.single().capped)
        assertEquals(false, WalletFlags(flagsFile).asksCaps(id))
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
    }

    @Test
    fun tryAgainReadsTheSettingsFileAgainAndAFailedResetOnAReadableFileEndsTheUnknownState() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id, cappedAlready = true)
        flagsFile.bytes = unreadable.encodeToByteArray()
        val m = manager()
        m.open()
        assertTrue(WalletsManager.UNKNOWN in m.capsPending.value)
        // The file reads now: re-reading ends the unknown state.
        flagsFile.bytes = null
        m.rereadSettings()
        assertTrue(m.capsPending.value.isEmpty())
        assertTrue(m.receiveAddress(id) is ReceiveResult.Address)
        // Unreadable again, then readable before the member confirms the reset: the reset is refused and the state is cleared.
        flagsFile.bytes = unreadable.encodeToByteArray()
        val other = manager()
        other.open()
        assertTrue(WalletsManager.UNKNOWN in other.capsPending.value)
        flagsFile.bytes = null
        assertFalse(other.resetSettings())
        assertTrue(other.capsPending.value.isEmpty())
        assertTrue(other.receiveAddress(id) is ReceiveResult.Address)
    }

    @Test
    fun aPendingSetRefusedAsADuplicateStaysPendingNamesItAndIsNotRetriedByItself() = runBlocking {
        val id = "01WALLET000000000000000001"
        wallet(id)
        WalletFlags(flagsFile).setPendingCaps(id, caps)
        ops.capsSetFails = "duplicate_wallet"
        val m = manager()
        m.open()
        assertEquals(1, ops.calls.count { it.startsWith("caps.set") })
        assertTrue(m.isCapsPending(id))
        assertEquals(setOf(id), m.capsDuplicate.value)
        assertEquals(caps, WalletFlags(flagsFile).pendingCaps(id))
        // Receive stays off, with the reason.
        assertEquals(ReceiveResult.CapsDuplicate, m.receiveAddress(id))
        // A raise is refused too, without a raise being sent.
        val e = runCatching { m.raiseCaps(id, m.capsSeen(id)!!, null, 3_000_000, "123456", "pw") }.exceptionOrNull() as VaultFailure
        assertEquals(WalletsManager.DUPLICATE_WALLET, e.code)
        assertTrue(ops.calls.none { it.startsWith("caps.raise") })
        // The twin is deleted: the next try sets it and everything clears.
        ops.capsSetFails = null
        m.settleCaps(id)
        assertFalse(m.isCapsPending(id))
        assertTrue(m.capsDuplicate.value.isEmpty())
    }

    @Test
    fun aWalletMadeAfterAFailedListDoesNotStandInForTheListAtTheReset() = runBlocking {
        val older = "01WALLET0000000000000000AA"
        wallet(older)
        flagsFile.bytes = unreadable.encodeToByteArray()
        // The list fails at unlock; the member then creates a wallet, and resets from it.
        ops.unsupported = false
        val m = manager()
        ops.listGate = kotlinx.coroutines.CompletableDeferred()
        val opening = CoroutineScope(Dispatchers.Default).launch { runCatching { m.open() } }
        kotlinx.coroutines.delay(300)
        val made = m.create("New", Network.TESTNET4, "pw", null).wallet
        assertEquals(listOf(made.id), m.wallets.value.map { it.id })
        assertTrue(m.resetSettings())
        // The older wallet is not in the file's ask list yet; the first real list fills it (the new wallet existed at the reset too).
        assertEquals(false, WalletFlags(flagsFile).asksCaps(older))
        ops.listGate!!.complete(Unit)
        opening.join()
        assertEquals(true, WalletFlags(flagsFile).asksCaps(older))
        assertEquals(ReceiveResult.LimitsAsk, m.receiveAddress(older))
        // A wallet made after the reset is still not asked.
        val later = m.create("Later", Network.TESTNET4, "pw", null).wallet
        assertEquals(false, WalletFlags(flagsFile).asksCaps(later.id))
    }
}
