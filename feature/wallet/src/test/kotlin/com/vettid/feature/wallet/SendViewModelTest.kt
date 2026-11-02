// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.lifecycle.SavedStateHandle
import com.vettid.core.chain.AddressFamily
import com.vettid.core.chain.BroadcastRefusal
import com.vettid.core.chain.ChainError
import com.vettid.core.chain.Network
import com.vettid.core.chain.PayeeCheck
import com.vettid.core.data.vault.FailureKind
import com.vettid.core.data.vault.VaultFailure
import com.vettid.core.wallet.BroadcastCause
import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.FeeEstimates
import com.vettid.core.wallet.PrepareResult
import com.vettid.core.wallet.RecentSends
import com.vettid.core.wallet.SendHold
import com.vettid.core.wallet.SendResult
import com.vettid.core.wallet.WalletCaps
import com.vettid.core.wallet.WalletFlags
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** Send's view model (ANDROID-PLAN §4 "Sending"): every step, hold and result. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass") // one test per step, hold and ending
class SendViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val ops = FakeWallets()
    private val flagsFile = MemoryFlags()
    private val engine = FakeEngine()
    private val id = "01WALLET0000000000000000AA"

    /** Changes the wallet the vault holds before the screen opens (a limit, say). */
    private var editWallet: (com.vettid.core.wallet.VaultWallet) -> com.vettid.core.wallet.VaultWallet = { it }

    private suspend fun TestScope.vm(backedUp: Boolean = true, network: String = "testnet4", noteShown: Boolean = true): SendViewModel {
        ops.wallet(id, network = network)
        ops.wallets[id] = editWallet(ops.wallets.getValue(id))
        val flags = WalletFlags(flagsFile)
        if (backedUp) flags.setBackedUp(id)
        if (noteShown) flags.setPrivacyNoteShown(id)
        val m = manager(backgroundScope, ops, flags)
        m.open()
        return SendViewModel(SavedStateHandle(mapOf("walletId" to id)), m, FakeServerSettings(), engine)
    }

    /** The form ready to review: a payee, 40,000 sats, Normal. */
    private suspend fun TestScope.ready(): SendViewModel {
        val vm = vm()
        eventually { vm.state.value.estimates != null }
        vm.setAddress(PAYEE)
        vm.setUnit(AmountUnit.SATS)
        vm.setAmount("40000")
        return vm
    }

    private suspend fun TestScope.review(): SendViewModel {
        val vm = ready()
        vm.review()
        eventually { vm.state.value.step == SendStep.REVIEW }
        return vm
    }

    // --- the holds ---

    @Test
    fun aWalletNotBackedUpShowsItsNoticeAndNoForm() = runTest {
        val vm = vm(backedUp = false)
        eventually { vm.state.value.block == SendBlock.NOT_BACKED_UP }
        assertTrue(engine.requests.isEmpty())
        assertNull(vm.state.value.estimates)
    }

    @Test
    fun aPendingLimitHoldsSendBack() = runTest {
        WalletFlags(flagsFile).setPendingCaps(id, WalletCaps(500_000, null))
        ops.capsSetFails = "internal"
        val vm = vm()
        eventually { vm.state.value.block == SendBlock.CAPS_PENDING }
        // Once it is set, Try again opens the form.
        ops.capsSetFails = null
        vm.load()
        eventually { vm.state.value.block == null && vm.state.value.estimates != null }
    }

    @Test
    fun unreadableSettingsHoldSendBackBeforeTheBackupGate() = runTest {
        flagsFile.failLoads = true
        val vm = vm(backedUp = false)
        eventually { vm.state.value.block == SendBlock.SETTINGS_UNREADABLE }
    }

    @Test
    fun aLegacyTestnetWalletIsAskedItsNetworkAndANetworkNotOfferedIsSaid() = runTest {
        val vm = vm(network = "testnet")
        eventually { vm.state.value.block == SendBlock.NEEDS_NETWORK }
        vm.chooseTestNetwork(Network.TESTNET4)
        eventually { vm.state.value.block == null }
        val mainnet = vm(network = "mainnet")
        eventually { mainnet.state.value.block == SendBlock.NOT_OFFERED }
    }

    @Test
    fun everySendHoldShowsItsOwnNotice() = runTest {
        val cases = mapOf(
            SendHold.CAPS_PENDING to SendBlock.CAPS_PENDING,
            SendHold.CAPS_DUPLICATE to SendBlock.CAPS_DUPLICATE,
            SendHold.SETTINGS_UNREADABLE to SendBlock.SETTINGS_UNREADABLE,
            SendHold.SIGN_UNSETTLED to SendBlock.SIGN_UNSETTLED,
        )
        for ((hold, block) in cases) {
            val vm = ready()
            engine.prepared = SendPrepared.Stopped(PrepareResult.Held(hold))
            vm.review()
            eventually { vm.state.value.block == block }
            assertFalse(vm.state.value.busy)
        }
    }

    @Test
    fun otherStopsThatAreNoticesAreMapped() = runTest {
        val cases = mapOf(
            PrepareResult.NeedsNetwork to SendBlock.NEEDS_NETWORK,
            PrepareResult.NotOffered to SendBlock.NOT_OFFERED,
            PrepareResult.NoteNeeded to SendBlock.NOTE_NEEDED,
            PrepareResult.AddressMismatch to SendBlock.MISMATCH,
        )
        for ((result, block) in cases) {
            val vm = ready()
            engine.prepared = SendPrepared.Stopped(result)
            vm.review()
            eventually { vm.state.value.block == block }
        }
    }

    @Test
    fun aSigningLostAcrossARestartIsAHoldWithTheTellingText() = runTest {
        val vm = ready()
        engine.prepared = SendPrepared.Stopped(PrepareResult.Held(SendHold.SIGN_UNSETTLED))
        vm.review()
        eventually { vm.state.value.block == SendBlock.SIGN_UNSETTLED }
        // Try again reads the holds again: the one from the prepare is gone once the screen is asked to look.
        vm.load()
        eventually { vm.state.value.block == null }
    }

    @Test
    fun theChainServersNoteGatesSendAndTryAgainAsksAgain() = runTest {
        val vm = vm(noteShown = false)
        eventually { vm.state.value.block == SendBlock.NOTE_NEEDED }
        // It was shown on the detail screen meanwhile: Try again re-checks and opens the form.
        WalletFlags(flagsFile).setPrivacyNoteShown(id)
        vm.load()
        eventually { vm.state.value.block == null }
    }

    @Test
    fun aNewPaymentWhileOneIsUnsentShowsTheUnsentNoticeUnderTheCheckRule() = runTest {
        val vm = ready()
        engine.mustCheck = true
        engine.checkResult = SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout()))
        engine.prepared = SendPrepared.Stopped(PrepareResult.Unsent)
        vm.review()
        eventually { vm.state.value.outcome == SendOutcome.Unsent && engine.checks >= 1 && !vm.state.value.checking }
        assertEquals(SendStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.discardLocked)
        vm.askDiscard()
        eventually { !vm.state.value.checking }
        assertFalse(vm.state.value.discardAsk)
        assertEquals(0, engine.discards)
        // Nothing on the network: Discard is there.
        engine.mustCheck = false
        engine.checkResult = SendResult.NotOnServer
        vm.askDiscard()
        eventually { !vm.state.value.discardLocked && !vm.state.value.checking }
        vm.askDiscard()
        assertTrue(vm.state.value.discardAsk)
    }

    // --- who and how much ---

    @Test
    fun thePayeeFieldTrimsSpacesAtBothEnds() = runTest {
        val vm = vm()
        eventually { vm.state.value.network != null }
        vm.setAddress("  $PAYEE \n")
        assertEquals(PAYEE, vm.state.value.address)
        assertNull(vm.state.value.addressIssue)
        // What is sent is the trimmed address.
        vm.setUnit(AmountUnit.SATS)
        vm.setAmount("40000")
        eventually { vm.state.value.estimates != null }
        vm.review()
        eventually { engine.requests.isNotEmpty() }
        assertEquals(PAYEE, engine.requests.single().address)
    }

    @Test
    fun aBip21UriGivesItsAddressAndAmountAndNothingElse() = runTest {
        val vm = vm()
        eventually { vm.state.value.network != null }
        vm.setAddress(" bitcoin:$PAYEE?label=Rent&amount=0.0012&message=x&other-param=1 ")
        assertEquals(PAYEE, vm.state.value.address)
        assertEquals("0.0012", vm.state.value.amountText)
        assertEquals(AmountUnit.BTC, vm.state.value.unit)
        assertEquals(120_000L, vm.state.value.amountSats)
        // A scan is the same, and our own QR code (capitals) is read in lower case.
        vm.scanned("BITCOIN:" + PAYEE.uppercase() + "?amount=0.5")
        assertEquals(PAYEE, vm.state.value.address)
        assertEquals(50_000_000L, vm.state.value.amountSats)
        assertFalse(vm.state.value.scanning)
        // A valid link without an amount clears the field (what was typed before is not the link's).
        vm.scanned("bitcoin:$PAYEE")
        assertEquals("", vm.state.value.amountText)
        assertNull(vm.state.value.amountSats)
        assertFalse(vm.state.value.linkRefused)
        // A plain address, typed, leaves the amount alone.
        vm.setAmount("0.5")
        vm.setAddress(PAYEE)
        assertEquals("0.5", vm.state.value.amountText)
    }

    @Test
    fun aLinkWithSomethingUnsafeIsRefusedWholeAndFillsNothingIn() = runTest {
        val vm = vm()
        eventually { vm.state.value.network != null }
        vm.setAddress(PAYEE)
        vm.setAmount("0.5")
        val before = vm.state.value
        val bad = listOf(
            "bitcoin:$PAYEE?req-network=main&amount=0.1",
            "bitcoin:$PAYEE?REQ-x=1",
            "bitcoin:$PAYEE?amount=-1",
            "bitcoin:$PAYEE?amount=0.123456789",
            "bitcoin:$PAYEE?amount=abc",
            "bitcoin:$PAYEE?amount=",
            "bitcoin:$PAYEE?amount=0.1&amount=0.2",
        )
        for (link in bad) {
            vm.setAddress(link)
            assertTrue(link, vm.state.value.linkRefused)
            // Nothing from the link, and nothing of the earlier payee either: Review would go to the old one.
            assertEquals(link, "", vm.state.value.address)
            assertEquals(link, "", vm.state.value.amountText)
            assertFalse(link, vm.state.value.canReview)
            vm.review()
            assertTrue(link, engine.requests.isEmpty())
            vm.setAddress(PAYEE)
            vm.setAmount("0.5")
            assertEquals(before.address, vm.state.value.address)
            vm.scanned(link)
            assertTrue(link, vm.state.value.linkRefused)
            assertEquals(link, "", vm.state.value.address)
            vm.setAddress(PAYEE)
            vm.setAmount("0.5")
        }
        // Other unknown parameters are still ignored, and a good link clears the notice.
        vm.setAddress("bitcoin:$PAYEE?label=x&foo=bar&amount=0.1")
        assertFalse(vm.state.value.linkRefused)
        assertEquals("0.1", vm.state.value.amountText)
        assertFalse("tb1" in SendInput.parse("bitcoin:$PAYEE?req-x=1").address)
    }

    @Test
    fun scanningOpensAndClosesTheScanner() = runTest {
        val vm = vm()
        vm.startScan()
        assertTrue(vm.state.value.scanning)
        vm.scanUnavailable()
        assertTrue(vm.state.value.scanUnavailable)
        vm.stopScan()
        assertFalse(vm.state.value.scanning)
    }

    @Test
    fun anAddressOfAnotherNetworkIsRefusedAsItIsTyped() = runTest {
        val vm = vm()
        eventually { vm.state.value.network == Network.TESTNET4 }
        vm.setAddress(MAINNET_PAYEE)
        assertEquals(AddressIssue.WrongNetwork(AddressFamily.MAINNET), vm.state.value.addressIssue)
        assertFalse(vm.state.value.canReview)
        vm.setAddress("not an address")
        assertEquals(AddressIssue.NotAnAddress, vm.state.value.addressIssue)
        vm.setAddress(PAYEE)
        assertNull(vm.state.value.addressIssue)
        assertEquals(PayeeCheck.Ok::class, com.vettid.core.chain.Payee.check(Network.TESTNET4, PAYEE)::class)
    }

    @Test
    fun theAmountIsInBtcOrSatsAndConvertsWhenTheUnitChanges() = runTest {
        val vm = vm()
        vm.setAmount("0.0012x3")
        assertEquals("0.00123", vm.state.value.amountText)
        assertEquals(123_000L, vm.state.value.amountSats)
        vm.setUnit(AmountUnit.SATS)
        assertEquals("123000", vm.state.value.amountText)
        vm.setAmount("12a")
        assertEquals("12", vm.state.value.amountText)
        vm.setAmount("0")
        assertEquals(BtcFormat.Amount.Invalid, vm.state.value.amount)
        vm.setAmount("100000000")
        vm.setUnit(AmountUnit.BTC)
        assertEquals("1", vm.state.value.amountText)
        vm.setAmount("0.123456789")
        assertEquals(BtcFormat.Amount.Invalid, vm.state.value.amount)
    }

    @Test
    fun sendAllNeedsNoAmountAndSendsNone() = runTest {
        val vm = ready()
        vm.setAmount("")
        assertFalse(vm.state.value.canReview)
        vm.setSendAll(true)
        assertTrue(vm.state.value.canReview)
        vm.review()
        eventually { engine.requests.isNotEmpty() }
        assertNull(engine.requests.single().amountSats)
    }

    // --- the fee ---

    @Test
    fun theFeeIsSlowNormalFastOrCustomBetweenOneAndAThousand() = runTest {
        val vm = ready()
        assertEquals(5.0, vm.state.value.feeRate!!, 0.0)
        vm.setFeeChoice(FeeChoice.FAST)
        assertEquals(12.0, vm.state.value.feeRate!!, 0.0)
        vm.setFeeChoice(FeeChoice.SLOW)
        assertEquals(1.0, vm.state.value.feeRate!!, 0.0)
        vm.setFeeChoice(FeeChoice.CUSTOM)
        assertNull(vm.state.value.feeRate)
        assertFalse(vm.state.value.canReview)
        for (bad in listOf("0", "0.5", "1001", "5000")) {
            vm.setCustomFee(bad)
            assertNull(bad, vm.state.value.feeRate)
            assertTrue(bad, vm.state.value.customFeeBad)
        }
        for ((text, rate) in listOf("1" to 1.0, "1000" to 1000.0, "2.5" to 2.5)) {
            vm.setCustomFee(text)
            assertEquals(rate, vm.state.value.feeRate!!, 0.0)
            assertFalse(vm.state.value.customFeeBad)
        }
        vm.setCustomFee("1x2")
        assertEquals("12", vm.state.value.customFee)
    }

    @Test
    fun estimatesThatCannotBeReadLeaveCustomAndTryAgain() = runTest {
        engine.estimates = FeeEstimates.Failed(ChainError.Timeout())
        val vm = vm()
        eventually { vm.state.value.estimatesFailed }
        assertNull(vm.state.value.feeRate)
        vm.setFeeChoice(FeeChoice.CUSTOM)
        vm.setCustomFee("3")
        assertEquals(3.0, vm.state.value.feeRate!!, 0.0)
        engine.estimates = FeeEstimates.Done(mapOf(2 to 12.0, 6 to 5.0, 144 to 1.0))
        vm.retryFees()
        eventually { vm.state.value.estimates != null }
        assertFalse(vm.state.value.estimatesFailed)
    }

    @Test
    fun aFeeAboveTwiceFastIsAskedAboutFirst() = runTest {
        val vm = ready()
        vm.setFeeChoice(FeeChoice.CUSTOM)
        // Twice Fast (12) is 24: equal is fine, above asks.
        vm.setCustomFee("24")
        vm.review()
        eventually { engine.requests.size == 1 }
        assertNull(vm.state.value.bigFeeAsk)
        vm.edit()
        vm.setCustomFee("25")
        vm.review()
        assertEquals(BigFee.MUCH_HIGHER, vm.state.value.bigFeeAsk)
        assertEquals(1, engine.requests.size)
        vm.cancelBigFee()
        assertNull(vm.state.value.bigFeeAsk)
        vm.review()
        vm.confirmBigFee()
        eventually { vm.state.value.step == SendStep.REVIEW }
        assertEquals(25.0, engine.requests.last().feeRate, 0.0)
    }

    @Test
    fun aFeeOverTenPercentOfTheAmountIsAskedAboutWithItsOwnText() = runTest {
        // 40,000 sats; the smallest transaction (110 vB) at 36 sat/vB is 3,960 (not over 10%), at 37 it is 4,070 (over).
        engine.estimates = FeeEstimates.Done(mapOf(2 to 100.0, 6 to 50.0, 144 to 1.0))
        val vm = ready()
        vm.setFeeChoice(FeeChoice.CUSTOM)
        vm.setCustomFee("36")
        vm.review()
        eventually { engine.requests.size == 1 && vm.state.value.step == SendStep.REVIEW }
        assertNull(vm.state.value.bigFeeAsk)
        vm.edit()
        vm.setCustomFee("37")
        vm.review()
        assertEquals(BigFee.TEN_PERCENT, vm.state.value.bigFeeAsk)
        assertEquals(1, engine.requests.size)
        vm.cancelBigFee()
        vm.review()
        vm.confirmBigFee()
        eventually { vm.state.value.step == SendStep.REVIEW }
        // Confirmed once: the review does not ask again for this payment.
        assertNull(vm.state.value.bigFeeAsk)
        // Both guards apply (above twice Fast and over 10%): one question, the 10% text.
        vm.edit()
        vm.setCustomFee("200")
        vm.review()
        assertEquals(BigFee.TEN_PERCENT, vm.state.value.bigFeeAsk)
    }

    @Test
    fun theVaultsOwnFeeOverTenPercentIsAskedAboutAtTheReview() = runTest {
        // Send all: the amount is not known before the build. 4,000 of 40,000 is exactly 10% (fine); 4,001 is over.
        val vm = ready()
        vm.setSendAll(true)
        engine.prepared = SendPrepared.Ready(sampleReview().let { Review(it.address, 40_000, 0, 4_000, 150, 44_000, 0, true, 0, true) })
        vm.review()
        eventually { vm.state.value.step == SendStep.REVIEW }
        assertNull(vm.state.value.bigFeeAsk)
        vm.edit()
        engine.prepared = SendPrepared.Ready(sampleReview().let { Review(it.address, 40_000, 0, 4_001, 150, 44_001, 0, true, 0, true) })
        vm.review()
        eventually { vm.state.value.bigFeeAsk == BigFee.TEN_PERCENT }
        // No approving while the question stands.
        vm.setPassword("pw")
        vm.approve()
        assertTrue(engine.passwords.isEmpty())
        vm.confirmBigFee()
        assertEquals(SendStep.REVIEW, vm.state.value.step)
        vm.approve()
        eventually { vm.state.value.step == SendStep.DONE }
        // Cancelling goes back to the form.
        val other = ready()
        other.setSendAll(true)
        other.review()
        eventually { other.state.value.bigFeeAsk == BigFee.TEN_PERCENT }
        other.cancelBigFee()
        assertEquals(SendStep.FORM, other.state.value.step)
    }

    // --- a payment the vault signed lately ---

    @Test
    fun aRecentPaymentWaitingOrPossiblyGoneThroughNeedsAcknowledgingBeforeANewOne() = runTest {
        for (notice in listOf(RecentSends.WAITING, RecentSends.MAY_HAVE_GONE, RecentSends.UNREADABLE)) {
            engine.recent = notice
            engine.requests.clear()
            val vm = vm()
            eventually { vm.state.value.recentNotice == notice }
            vm.setAddress(PAYEE)
            vm.setUnit(AmountUnit.SATS)
            vm.setAmount("40000")
            eventually { vm.state.value.estimates != null }
            // No new payment is prepared until it is acknowledged.
            assertFalse(vm.state.value.canReview)
            vm.review()
            assertTrue(engine.requests.isEmpty())
            vm.acknowledgeRecent()
            assertNull(vm.state.value.recentNotice)
            assertTrue(vm.state.value.canReview)
            vm.review()
            eventually { engine.requests.size == 1 }
        }
    }

    @Test
    fun nothingCanBeReviewedOrPreparedWhileTheRecentCheckRuns() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        engine.recentGate = gate
        val vm = vm()
        // The wait is real time here (the test clock would run the 20 s out at once).
        vm.recentWaitMs = null
        eventually { vm.state.value.recentChecking && vm.state.value.estimates != null }
        vm.setAddress(PAYEE)
        vm.setUnit(AmountUnit.SATS)
        vm.setAmount("40000")
        assertFalse(vm.state.value.canReview)
        vm.review()
        assertTrue(engine.requests.isEmpty())
        gate.complete(Unit)
        eventually { !vm.state.value.recentChecking }
        assertTrue(vm.state.value.canReview)
        vm.review()
        eventually { engine.requests.size == 1 }
    }

    @Test
    fun theCheckRunsAgainWheneverAHoldClears() = runTest {
        // First look: a hold stands, nothing is checked. Try again and a hold lifting each run the check.
        val vm = vm(backedUp = false)
        eventually { vm.state.value.block == SendBlock.NOT_BACKED_UP }
        assertEquals(0, engine.recentAsked)
        WalletFlags(flagsFile).setBackedUp(id)
        vm.load()
        eventually { vm.state.value.block == null && engine.recentAsked == 1 && !vm.state.value.recentChecking }
        // Try again once more (a block that was set by a prepare): the check runs again.
        vm.load()
        eventually { engine.recentAsked == 2 && !vm.state.value.recentChecking }
    }

    @Test
    fun aHoldLiftingByItselfRunsTheCheck() = runTest {
        val vm = vm(noteShown = false)
        eventually { vm.state.value.block == SendBlock.NOTE_NEEDED }
        assertEquals(0, engine.recentAsked)
        // The note is shown on the detail screen: the manager's flags move and the screen re-reads its holds.
        val m = manager(backgroundScope, ops, WalletFlags(flagsFile))
        m.open()
        m.setPrivacyNoteShown(id)
        vm.load()
        eventually { vm.state.value.block == null && engine.recentAsked == 1 }
    }

    @Test
    fun theCoreAskingForTheCheckRunsItAndTheNoticeFollows() = runTest {
        val vm = ready()
        eventually { engine.recentAsked == 1 && !vm.state.value.recentChecking }
        engine.recent = RecentSends.WAITING
        engine.recentLatest = com.vettid.core.wallet.WalletTx("ab".repeat(32), Instant.parse("2026-11-02T09:00:00Z"), 40_000, 1, 1, emptyList(), null, null)
        engine.prepared = SendPrepared.Stopped(PrepareResult.RecentUnchecked)
        vm.review()
        eventually { vm.state.value.recentNotice == RecentSends.WAITING && !vm.state.value.recentChecking }
        assertEquals(2, engine.recentAsked)
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun acknowledgingTellsTheCore() = runTest {
        engine.recent = RecentSends.UNREADABLE
        val vm = vm()
        eventually { vm.state.value.recentNotice == RecentSends.UNREADABLE }
        vm.acknowledgeRecent()
        assertEquals(listOf(id), engine.acknowledged)
        assertNull(vm.state.value.recentNotice)
    }

    @Test
    fun aCheckThatIsCancelledNeverLeavesReviewOffForGood() = runTest {
        engine.recentThrows = kotlinx.coroutines.CancellationException("gone")
        val vm = vm()
        eventually { engine.recentAsked >= 1 && !vm.state.value.recentChecking }
        assertFalse(vm.state.value.recentChecking)
        // The next look works again.
        engine.recentThrows = null
        vm.load()
        eventually { engine.recentAsked == 2 && !vm.state.value.recentChecking }
        vm.setAddress(PAYEE)
        vm.setUnit(AmountUnit.SATS)
        vm.setAmount("40000")
        eventually { vm.state.value.estimates != null }
        assertTrue(vm.state.value.canReview)
    }

    @Test
    fun aCheckThatThrowsOrNeverEndsIsUnreadableAndNeverCrashes() = runTest {
        engine.recentThrows = IllegalArgumentException("not a txid")
        val vm = vm()
        eventually { vm.state.value.recentNotice == RecentSends.UNREADABLE && !vm.state.value.recentChecking }
        engine.recentThrows = com.vettid.core.wallet.WalletWireException("transactions")
        vm.load()
        eventually { engine.recentAsked == 2 && !vm.state.value.recentChecking }
        assertEquals(RecentSends.UNREADABLE, vm.state.value.recentNotice)
    }

    @Test
    fun aFeeWellAboveTheOneAgreedToIsAskedAboutAgainOnTheReview() = runTest {
        engine.estimates = FeeEstimates.Done(mapOf(2 to 100.0, 6 to 50.0, 144 to 1.0))
        val vm = ready()
        vm.setFeeChoice(FeeChoice.CUSTOM)
        vm.setCustomFee("40")
        vm.review()
        // 40 sat/vB x 110 = 4,400 over 10% of 40,000: asked, naming the figure; confirmed.
        assertEquals(BigFee.TEN_PERCENT, vm.state.value.bigFeeAsk)
        assertEquals(4_400L, vm.state.value.bigFeeSats)
        // The vault's real fee is 7,000 (more than 1.5 times 4,400 = 6,600): asked again, with its figure.
        engine.prepared = SendPrepared.Ready(Review(PAYEE, 40_000, 0, 7_000, 150, 47_000, 0, false, 0, true))
        vm.confirmBigFee()
        eventually { vm.state.value.step == SendStep.REVIEW && vm.state.value.bigFeeAsk == BigFee.TEN_PERCENT }
        assertEquals(7_000L, vm.state.value.bigFeeSats)
        vm.confirmBigFee()
        assertNull(vm.state.value.bigFeeAsk)
    }

    @Test
    fun nothingRecentShowsNoNotice() = runTest {
        engine.recent = RecentSends.CLEAR
        val vm = vm()
        eventually { vm.state.value.estimates != null && engine.recentAsked == 1 }
        assertNull(vm.state.value.recentNotice)
    }

    @Test
    fun theCheckRunsOnlyOnceAHoldIsLifted() = runTest {
        val vm = vm(backedUp = false)
        eventually { vm.state.value.block == SendBlock.NOT_BACKED_UP }
        assertEquals(0, engine.recentAsked)
    }

    @Test
    fun unconfirmedCoinsAreOffByDefaultAndSent() = runTest {
        val vm = ready()
        assertFalse(vm.state.value.includeUnconfirmed)
        vm.review()
        eventually { engine.requests.size == 1 }
        assertFalse(engine.requests.single().includeUnconfirmed)
        vm.edit()
        vm.setIncludeUnconfirmed(true)
        vm.review()
        eventually { engine.requests.size == 2 }
        assertTrue(engine.requests.last().includeUnconfirmed)
    }

    // --- review ---

    @Test
    fun theReviewShowsTheVaultsSummaryAndNothingIsSigned() = runTest {
        val vm = review()
        val r = vm.state.value.review!!
        assertEquals(40_000L, r.payeeSats)
        assertEquals(1_200L, r.feeSats)
        assertEquals(41_200L, r.totalOutSats)
        assertEquals(6.0, r.feeRate, 0.0)
        assertTrue(engine.passwords.isEmpty())
        assertEquals("", vm.state.value.password)
    }

    @Test
    fun theLimitLineIsShownOnlyForAVaultWithAPerPeriodLimit() = runTest {
        // No caps on the wallet: nothing about limits.
        val none = review()
        assertNull(none.state.value.review!!.limit)
        // A per-period limit with 750,000 spent: outflow 41,200, left 2,000,000 - 750,000 - 41,200.
        editWallet = { it.copy(perPeriodSats = 2_000_000, spentSats = 750_000, capped = true) }
        val vm = review()
        eventually { vm.state.value.review?.limit != null }
        assertEquals(LimitLine(41_200, 1_208_800), vm.state.value.review!!.limit)
        // A vault that returns no cap says nothing, even with limits on the wallet.
        engine.prepared = SendPrepared.Ready(sampleReview(hasCap = false))
        vm.edit()
        vm.review()
        eventually { vm.state.value.review?.hasCap == false }
        assertNull(vm.state.value.review!!.limit)
    }

    @Test
    fun theLimitLeftNeverGoesBelowZero() = runTest {
        editWallet = { it.copy(perPeriodSats = 100_000, spentSats = 90_000, capped = true) }
        val vm = review()
        assertEquals(LimitLine(41_200, 0), vm.state.value.review!!.limit)
    }

    @Test
    fun aCapRefusalInTheInspectIsShownBeforeThePasswordAndApproveIsOff() = runTest {
        engine.prepared = SendPrepared.Ready(sampleReview(capRefusal = CapText(CapReason.PER_TX, 30_000, null, null)))
        val vm = review()
        assertEquals(CapReason.PER_TX, vm.state.value.review!!.capRefusal!!.reason)
        vm.setPassword("pw")
        vm.approve()
        assertTrue(engine.passwords.isEmpty())
    }

    @Test
    fun aCapWithoutTheLimitTakesTheWalletsOwn() = runTest {
        editWallet = { it.copy(perTxSats = 30_000, capped = true) }
        engine.prepared = SendPrepared.Ready(sampleReview(capRefusal = CapText(CapReason.PER_TX, null, null, null)))
        val vm = review()
        assertEquals(30_000L, vm.state.value.review!!.capRefusal!!.limitSats)
    }

    @Test
    fun theStopsOfThePrepareStayOnTheFormWithTheirProblem() = runTest {
        val cases = listOf(
            PrepareResult.SelfSend to SendProblem.SelfSend,
            PrepareResult.BadFeeRate to SendProblem.BadFee,
            PrepareResult.BadAmount to SendProblem.BadAmount,
            PrepareResult.TooManyInputs to SendProblem.TooManyInputs,
            PrepareResult.NotEnough(10, 20) to SendProblem.NotEnough(10, 20),
            PrepareResult.Different(emptySet()) to SendProblem.Different,
            PrepareResult.Refused("fee_rate", alarm = false, byVault = true) to SendProblem.Refused("fee_rate", false, true),
            PrepareResult.Refused("wrong_seed", alarm = true, byVault = true) to SendProblem.Refused("wrong_seed", true, true),
            PrepareResult.ServerFailed(ChainError.Timeout()) to SendProblem.ServerFailed,
        )
        for ((result, problem) in cases) {
            val vm = ready()
            engine.prepared = SendPrepared.Stopped(result)
            vm.review()
            eventually { vm.state.value.problem != null }
            assertEquals(problem, vm.state.value.problem)
            assertEquals(SendStep.FORM, vm.state.value.step)
            assertFalse(vm.state.value.busy)
            // Typing again clears it.
            vm.setAmount("41000")
            assertNull(vm.state.value.problem)
        }
    }

    @Test
    fun aBadAddressFromThePrepareIsTheAddressFieldsProblem() = runTest {
        val vm = ready()
        engine.prepared = SendPrepared.Stopped(PrepareResult.BadAddress(PayeeCheck.Invalid))
        vm.review()
        eventually { vm.state.value.addressIssue == AddressIssue.NotAnAddress }
    }

    @Test
    fun aPrepareThatTheVaultFailsIsShownAsItsFailure() = runTest {
        val vm = ready()
        engine.prepareFailure = VaultFailure(FailureKind.NO_RESPONSE)
        vm.review()
        eventually { vm.state.value.problem == SendProblem.Failed(FailureKind.NO_RESPONSE) }
        assertFalse(vm.state.value.busy)
    }

    @Test
    fun backFromTheReviewKeepsTheFormAndDropsThePreparedPayment() = runTest {
        val vm = review()
        val forgets = engine.forgets
        vm.edit()
        assertEquals(SendStep.FORM, vm.state.value.step)
        assertNull(vm.state.value.review)
        assertEquals(PAYEE, vm.state.value.address)
        assertTrue(engine.forgets > forgets)
    }

    // --- approve ---

    @Test
    fun approveSendsThePasswordOnceAndWipesItAtOnce() = runTest {
        val vm = review()
        vm.setPassword("pw")
        vm.approve()
        assertEquals("", vm.state.value.password)
        eventually { vm.state.value.step == SendStep.DONE }
        assertEquals(listOf("pw"), engine.passwords)
        assertEquals("", vm.state.value.password)
        assertEquals(SendOutcome.Sent(ConfirmTime.HOUR), vm.state.value.outcome)
        assertFalse("pw" in vm.state.value.toString())
    }

    @Test
    fun approveNeedsAPasswordAndNothingElseRuns() = runTest {
        val vm = review()
        vm.approve()
        assertTrue(engine.passwords.isEmpty())
        assertEquals(SendStep.REVIEW, vm.state.value.step)
    }

    @Test
    fun theTimeComesFromTheFeeTarget() = runTest {
        for ((choice, time) in listOf(FeeChoice.SLOW to ConfirmTime.DAY, FeeChoice.NORMAL to ConfirmTime.HOUR, FeeChoice.FAST to ConfirmTime.MINUTES_20)) {
            val vm = ready()
            vm.setFeeChoice(choice)
            vm.review()
            eventually { vm.state.value.step == SendStep.REVIEW }
            vm.setPassword("pw")
            vm.approve()
            eventually { vm.state.value.outcome != null }
            assertEquals(SendOutcome.Sent(time), vm.state.value.outcome)
        }
        // A custom rate: the quickest target it reaches.
        val est = mapOf(2 to 12.0, 6 to 5.0, 144 to 1.0)
        assertEquals(ConfirmTime.MINUTES_20, SendFees.timeFor(12.0, est))
        assertEquals(ConfirmTime.HOUR, SendFees.timeFor(11.9, est))
        assertEquals(ConfirmTime.DAY, SendFees.timeFor(4.9, est))
    }

    @Test
    fun aWrongPasswordLeavesTheReviewToTypeItAgain() = runTest {
        val vm = review()
        engine.approveFailure = VaultFailure(FailureKind.BAD_PASSWORD, "bad_password")
        vm.setPassword("nope")
        vm.approve()
        eventually { vm.state.value.error == FailureKind.BAD_PASSWORD }
        assertEquals(SendStep.REVIEW, vm.state.value.step)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.state.value.busy)
        engine.approveFailure = null
        vm.setPassword("pw")
        assertNull(vm.state.value.error)
        vm.approve()
        eventually { vm.state.value.step == SendStep.DONE }
    }

    @Test
    fun aWindowThatClosedAsksForThePasswordOnce() = runTest {
        val vm = review()
        engine.approveResult = SendResult.CredentialLocked
        vm.setPassword("pw")
        vm.approve()
        eventually { vm.state.value.askAgain }
        assertEquals(SendStep.REVIEW, vm.state.value.step)
    }

    // --- results ---

    private suspend fun TestScope.approved(result: SendResult): SendViewModel {
        engine.approveResult = result
        val vm = review()
        vm.setPassword("pw")
        vm.approve()
        eventually { vm.state.value.step == SendStep.DONE || vm.state.value.block != null || vm.state.value.closed }
        return vm
    }

    @Test
    fun aBroadcastThatWasRefusedOffersTryAgainAnotherServerAndDiscard() = runTest {
        val vm = approved(SendResult.BroadcastFailed(BroadcastCause.Refused(com.vettid.core.chain.BroadcastRefusal.FEE_TOO_LOW)))
        val o = vm.state.value.outcome as SendOutcome.BroadcastFailed
        assertEquals(BroadcastReason.FEE_TOO_LOW, o.reason)
        // A refusal is the server's definite word: no question about the transaction, discard is there.
        assertEquals(0, engine.checks)
        assertFalse(vm.state.value.discardLocked)
        vm.retry()
        eventually { vm.state.value.outcome is SendOutcome.Sent }
        assertEquals(1, engine.retries)
    }

    @Test
    fun aBroadcastThatGotNoAnswerAsksTheServerBeforeDiscardIsOffered() = runTest {
        engine.mustCheck = true
        engine.checkResult = SendResult.NotOnServer
        val vm = approved(SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout())))
        eventually { engine.checks == 1 && !vm.state.value.discardLocked }
        assertFalse(vm.state.value.checking)
        vm.askDiscard()
        assertTrue(vm.state.value.discardAsk)
    }

    @Test
    fun aServerThatCannotSayLeavesDiscardOffAndTheViewModelRefusesIt() = runTest {
        engine.mustCheck = true
        engine.checkResult = SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout()))
        val vm = approved(SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout())))
        eventually { engine.checks == 1 && !vm.state.value.checking }
        assertTrue(vm.state.value.discardLocked)
        assertTrue((vm.state.value.outcome as SendOutcome.BroadcastFailed).mayBeSent)
        // Enforced in the view model, not only by the button: no confirm opens and nothing is dropped.
        vm.askDiscard()
        eventually { engine.checks >= 2 && !vm.state.value.checking }
        assertFalse(vm.state.value.discardAsk)
        vm.discard()
        assertEquals(0, engine.discards)
        assertEquals(SendStep.DONE, vm.state.value.step)
    }

    @Test
    fun aTransactionTheServerHasAfterAllIsTreatedAsSent() = runTest {
        engine.mustCheck = true
        engine.checkResult = SendResult.Sent("ab".repeat(32), alreadyKnown = true)
        val vm = approved(SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout())))
        eventually { vm.state.value.outcome is SendOutcome.Sent }
        assertEquals(1, engine.checks)
        assertFalse(vm.state.value.discardLocked)
    }

    // --- B1: the three ways round the check ---

    @Test
    fun aPaymentFromAnEarlierVisitIsCheckedBeforeDiscardIsOffered() = runTest {
        // (a) Back after a BroadcastFailed(Server), then return: Unsent, and the check runs first.
        engine.unsent = true
        engine.mustCheck = true
        engine.checkResult = SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout()))
        val vm = vm()
        eventually { vm.state.value.outcome == SendOutcome.Unsent && engine.checks >= 1 && !vm.state.value.checking }
        assertTrue(vm.state.value.discardLocked)
        vm.askDiscard()
        eventually { !vm.state.value.checking }
        vm.discard()
        assertEquals(0, engine.discards)
        assertFalse(vm.state.value.discardAsk)
        // The server now says it does not have it: Discard opens and drops.
        engine.checkResult = SendResult.NotOnServer
        vm.askDiscard()
        eventually { !vm.state.value.discardLocked && !vm.state.value.checking }
        vm.askDiscard()
        assertTrue(vm.state.value.discardAsk)
        vm.discard()
        assertEquals(1, engine.discards)
        assertEquals(SendStep.FORM, vm.state.value.step)
    }

    @Test
    fun aSecondServerThatSaysNothingDefiniteDoesNotOpenDiscard() = runTest {
        // (b) Server A timed out and could not say; the member chose server B, which could not be opened: still locked.
        engine.mustCheck = true
        engine.checkResult = SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout()))
        val vm = approved(SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout())))
        eventually { engine.checks == 1 && !vm.state.value.checking }
        engine.retryResult = SendResult.BroadcastFailed(BroadcastCause.NoChain(com.vettid.core.wallet.ChainOpen.NoServer(com.vettid.core.chain.ChainServer.NotSet)))
        vm.retry()
        eventually { (vm.state.value.outcome as? SendOutcome.BroadcastFailed)?.reason == BroadcastReason.NO_SERVER && !vm.state.value.checking }
        assertTrue(vm.state.value.discardLocked)
        vm.askDiscard()
        eventually { !vm.state.value.checking }
        assertFalse(vm.state.value.discardAsk)
        assertEquals(0, engine.discards)
    }

    @Test
    fun anAlreadySignedPaymentWithAKeptTransactionIsCheckedBeforeDiscard() = runTest {
        // (c) AlreadySigned offers Discard as its primary action; with a kept transaction that may be on the network it waits for the check.
        engine.mustCheck = true
        engine.checkResult = SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout()))
        val vm = approved(SendResult.AlreadySigned)
        eventually { engine.checks >= 1 && !vm.state.value.checking }
        assertEquals(SendOutcome.AlreadySigned, vm.state.value.outcome)
        assertTrue(vm.state.value.discardLocked)
        vm.askDiscard()
        eventually { !vm.state.value.checking }
        vm.discard()
        assertEquals(0, engine.discards)
        engine.checkResult = SendResult.NotOnServer
        vm.askDiscard()
        eventually { !vm.state.value.discardLocked && !vm.state.value.checking }
        vm.askDiscard()
        assertTrue(vm.state.value.discardAsk)
        vm.discard()
        assertEquals(1, engine.discards)
    }

    @Test
    fun theCoreRefusingTheDropAfterTheAskStillLocksDiscard() = runTest {
        val vm = approved(SendResult.AlreadySigned)
        vm.askDiscard()
        assertTrue(vm.state.value.discardAsk)
        // The kept transaction became "may be on the network" between the ask and the confirm: the core refuses and the check runs.
        engine.mustCheck = true
        engine.checkResult = SendResult.BroadcastFailed(BroadcastCause.Server(ChainError.Timeout()))
        vm.discard()
        eventually { vm.state.value.discardLocked && !vm.state.value.checking }
        assertEquals(SendStep.DONE, vm.state.value.step)
        assertEquals(0, engine.discards)
    }

    // --- S2, S3, S4 ---

    @Test
    fun anOkThatDoesNotReadIsAPlainNoticeAndDiscardRule() = runTest {
        val vm = approved(SendResult.SignedUnreadable)
        assertEquals(SendOutcome.SignedUnreadable, vm.state.value.outcome)
    }

    @Test
    fun nothingThatIsNotAFailureOfTheVaultReachesTheTopLevel() = runTest {
        // An exception of any other kind (a parse error, a bug) after the request may have gone out: the notice "may have been signed".
        val vm = review()
        engine.approveThrows = IllegalStateException("odd")
        vm.setPassword("pw")
        vm.approve()
        eventually { vm.state.value.outcome == SendOutcome.SignedUnreadable }
        assertFalse(vm.state.value.busy)
        // A prepare that throws leaves the form with a problem.
        val form = ready()
        engine.prepareFailure = IllegalStateException("odd")
        form.review()
        eventually { form.state.value.problem == SendProblem.Failed(FailureKind.OTHER) }
        assertFalse(form.state.value.busy)
    }

    @Test
    fun backDoesNothingWhileARequestIsOut() = runTest {
        val vm = review()
        assertFalse(vm.state.value.swallowsBack)
        assertTrue(vm.state.value.copy(busy = true).swallowsBack)
        assertTrue(vm.state.value.copy(checking = true).swallowsBack)
    }

    @Test
    fun theReviewIsBuiltFromTheVaultsSummaryAlone() {
        val vault = com.vettid.core.chain.InspectSummary(
            txid = "aa".repeat(32),
            inputs = emptyList(),
            outputs = listOf(
                com.vettid.core.chain.SummaryOutput(PAYEE, 40_000, false, null, null),
                com.vettid.core.chain.SummaryOutput("tb1qchange", 58_800, true, "p2wpkh", "m/84'/1'/0'/1/0"),
            ),
            totalInSats = 100_000, sendingSats = 40_000, changeSats = 58_800, feeSats = 1_200, vsize = 150,
        )
        val r = ManagerSendEngine.reviewFrom(vault, null, toFeeSats = 330, sendAll = false, leftBehind = 0)!!
        // The vault's address as it wrote it, its amounts, its fee and its vsize (the rate is 1,200 / 150): nothing else.
        assertEquals(PAYEE, r.address)
        assertEquals(40_000L, r.payeeSats)
        assertEquals(58_800L, r.changeSats)
        assertEquals(1_200L, r.feeSats)
        assertEquals(150L, r.vsize)
        assertEquals(8.0, r.feeRate, 0.0)
        assertEquals(41_200L, r.totalOutSats)
        assertEquals(330L, r.toFeeSats)
        assertFalse(r.hasCap)
        assertNull(r.capRefusal)
        // No single payee in the summary: no review, and no fallback to what was entered.
        assertNull(ManagerSendEngine.reviewFrom(vault.copy(outputs = vault.outputs.filter { it.change }), null, 0, false, 0))
        assertNull(ManagerSendEngine.reviewFrom(vault.copy(outputs = vault.outputs + vault.outputs.first()), null, 0, false, 0))
    }

    @Test
    fun theCapOfTheInspectBecomesTheRefusalText() {
        val at = Instant.parse("2026-11-02T09:00:00Z")
        assertNull(ManagerSendEngine.refusalOf(com.vettid.core.wallet.SpendCap(true, null, null, null)))
        assertEquals(
            CapText(CapReason.PER_TX, 30_000, null, null),
            ManagerSendEngine.refusalOf(com.vettid.core.wallet.SpendCap(false, "per_tx", 999, at, limitSats = 30_000)),
        )
        assertEquals(
            CapText(CapReason.PER_PERIOD, 2_000_000, 300_000, at),
            ManagerSendEngine.refusalOf(com.vettid.core.wallet.SpendCap(false, "per_period", 300_000, at, limitSats = 2_000_000)),
        )
        assertEquals(
            CapText(CapReason.OTHER, null, null, null, other = "odd"),
            ManagerSendEngine.refusalOf(com.vettid.core.wallet.SpendCap(false, "odd", 5, at)),
        )
    }

    @Test
    fun noServerAtAllIsABroadcastFailureWithTheWayToChooseOne() = runTest {
        val vm = approved(SendResult.BroadcastFailed(BroadcastCause.NoChain(com.vettid.core.wallet.ChainOpen.NoServer(com.vettid.core.chain.ChainServer.NotSet))))
        assertEquals(BroadcastReason.NO_SERVER, (vm.state.value.outcome as SendOutcome.BroadcastFailed).reason)
    }

    @Test
    fun theBroadcastReasonsAreTheServersWords() {
        val cases = mapOf(
            BroadcastRefusal.INPUTS_MISSING_OR_SPENT to BroadcastReason.INPUTS_SPENT,
            BroadcastRefusal.CONFLICT to BroadcastReason.CONFLICT,
            BroadcastRefusal.DUST to BroadcastReason.DUST,
            BroadcastRefusal.SCRIPT_FAILED to BroadcastReason.SCRIPT_FAILED,
            BroadcastRefusal.MALFORMED to BroadcastReason.MALFORMED,
            BroadcastRefusal.OTHER to BroadcastReason.OTHER,
        )
        for ((refusal, reason) in cases) assertEquals(reason, BroadcastReason.of(BroadcastCause.Refused(refusal)))
    }

    @Test
    fun aCapExceededAtTheSigningIsShownWithItsLimitAndNothingWasSigned() = runTest {
        val at = Instant.parse("2026-11-02T09:00:00Z")
        val vm = approved(SendResult.CapExceeded(CapReason.PER_PERIOD, 2_000_000, 300_000, at))
        assertEquals(SendOutcome.CapExceeded(CapText(CapReason.PER_PERIOD, 2_000_000, 300_000, at)), vm.state.value.outcome)
        // Change the amount goes back to the form with what was typed.
        vm.startOver()
        assertEquals(SendStep.FORM, vm.state.value.step)
        assertEquals("40000", vm.state.value.amountText)
    }

    @Test
    fun aPerTxRefusalWithoutALimitTakesTheWalletsOwn() = runTest {
        editWallet = { it.copy(perTxSats = 30_000, capped = true) }
        val vm = approved(SendResult.CapExceeded(CapReason.PER_TX, null, null, null))
        assertEquals(30_000L, (vm.state.value.outcome as SendOutcome.CapExceeded).cap.limitSats)
    }

    @Test
    fun anInvalidPsbtAtTheSigningIsShownAsSuch() = runTest {
        val vm = approved(SendResult.Refused("derivation_key", alarm = false))
        assertEquals(SendOutcome.InvalidPsbt("derivation_key", false), vm.state.value.outcome)
        val alarm = approved(SendResult.Refused("wrong_seed", alarm = true))
        assertEquals(SendOutcome.InvalidPsbt("wrong_seed", true), alarm.state.value.outcome)
    }

    @Test
    fun theOtherEndingsAreEachTheirOwnNotice() = runTest {
        val cases = mapOf(
            SendResult.SignedDiffers to SendOutcome.SignedDiffers,
            SendResult.AlreadySigned to SendOutcome.AlreadySigned,
            SendResult.Unanswered to SendOutcome.Unanswered,
            SendResult.SignLapsed to SendOutcome.Lapsed,
            SendResult.Superseded to SendOutcome.Superseded,
            SendResult.DuplicateWallet to SendOutcome.DuplicateWallet,
        )
        for ((result, outcome) in cases) assertEquals(outcome, approved(result).state.value.outcome)
    }

    @Test
    fun aHoldAtTheApproveShowsItsNoticeAndAClearShutsTheScreen() = runTest {
        val held = approved(SendResult.Held(SendHold.SIGN_UNSETTLED))
        assertEquals(SendBlock.SIGN_UNSETTLED, held.state.value.block)
        val cleared = approved(SendResult.Cleared)
        assertTrue(cleared.state.value.closed)
        assertNull(cleared.state.value.review)
    }

    @Test
    fun anUnansweredSigningIsTriedAgainAndEndsInSentOrInALapse() = runTest {
        val vm = approved(SendResult.Unanswered)
        engine.retryResult = SendResult.Unanswered
        vm.retry()
        eventually { engine.retries == 1 && !vm.state.value.busy }
        assertEquals(SendOutcome.Unanswered, vm.state.value.outcome)
        // Past `exp` the core answers with a lapse: a definite ending, and Discard is still there.
        engine.retryResult = SendResult.SignLapsed
        vm.retry()
        eventually { vm.state.value.outcome == SendOutcome.Lapsed }
        vm.askDiscard()
        assertTrue(vm.state.value.discardAsk)
    }

    @Test
    fun discardIsAskedThenDropsThePaymentAndStartsOver() = runTest {
        val vm = approved(SendResult.AlreadySigned)
        vm.discard()
        assertEquals(0, engine.discards)
        vm.askDiscard()
        vm.cancelDiscard()
        assertFalse(vm.state.value.discardAsk)
        vm.askDiscard()
        vm.discard()
        assertEquals(1, engine.discards)
        assertEquals(SendStep.FORM, vm.state.value.step)
        assertNull(vm.state.value.outcome)
    }

    @Test
    fun aPaymentWaitingFromAnEarlierVisitWithNothingOnTheNetworkCanBeDiscarded() = runTest {
        engine.unsent = true
        val vm = vm()
        eventually { vm.state.value.outcome == SendOutcome.Unsent }
        assertEquals(SendStep.DONE, vm.state.value.step)
        assertFalse(vm.state.value.discardLocked)
        vm.askDiscard()
        vm.discard()
        assertEquals(SendStep.FORM, vm.state.value.step)
    }

    @Test
    fun theVaultClosingEmptiesTheScreen() = runTest {
        val vm = vm()
        eventually { vm.state.value.network != null }
        vm.setAddress(PAYEE)
        ops.wallets.clear()
        // A lock: the manager clears and the wallet is gone.
        assertNotNull(vm.state.value)
        assertFalse("tb1" in vm.state.value.toString())
    }

    @Test
    fun theStateAndItsPartsPrintTheirNamesOnly() = runTest {
        val vm = review()
        vm.setPassword("hunter2")
        val values = listOf<Any>(
            vm.state.value, vm.state.value.review!!, CapText(CapReason.PER_TX, 12345, 6789, null), LimitLine(12345, 6789),
            SendProblem.NotEnough(12345, 6789), SendOutcome.BroadcastFailed("orbit.example", BroadcastReason.OTHER),
            SendOutcome.InvalidPsbt("derivation_key", false), SendInput.parse("bitcoin:$PAYEE?amount=0.00012345"),
        )
        for (v in values) {
            for (s in listOf(PAYEE, "hunter2", "12345", "6789", "orbit.example", "derivation_key", "40000", "01WALLET")) {
                assertFalse("'$s' in $v", "$v".contains(s))
            }
        }
    }
}
