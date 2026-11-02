// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.vettid.core.chain.AddressFamily
import com.vettid.core.chain.Network
import com.vettid.core.wallet.CapReason
import com.vettid.core.wallet.RecentSends
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/** Send as the member sees it (ANDROID-PLAN §4 "Sending", "Texts (drafts)"). */
@RunWith(RobolectricTestRunner::class)
class SendScreenTest {
    @get:Rule
    val rule = createComposeRule()

    private val estimates = mapOf(2 to 12.0, 6 to 5.0, 144 to 1.0)

    private fun form(
        address: String = PAYEE,
        amount: String = "40000",
        estimates: Map<Int, Double>? = this.estimates,
        issue: AddressIssue? = null,
    ) = SendUiState(
        loading = false, network = Network.TESTNET4, address = address, addressIssue = issue, amountText = amount, unit = AmountUnit.SATS,
        estimates = estimates,
    )

    private fun review(r: Review = sampleReview(), password: String = "") = form().copy(step = SendStep.REVIEW, review = r, password = password)

    private fun done(o: SendOutcome, error: com.vettid.core.data.vault.FailureKind? = null) =
        form().copy(step = SendStep.DONE, outcome = o, error = error)

    private val shown = androidx.compose.runtime.mutableStateOf(form())
    private val acted = androidx.compose.runtime.mutableStateOf(SendActions())
    private var composed = false

    /** Shows [s]; a test that shows several states swaps them in the one composition. */
    private fun show(s: SendUiState, a: SendActions = SendActions()) {
        shown.value = s
        acted.value = a
        if (composed) {
            rule.waitForIdle()
        } else {
            composed = true
            rule.setContent { SendScreen(shown.value, acted.value, secure = false) }
        }
    }

    // --- the form ---

    @Test
    fun theFormHasTheAddressTheAmountTheFeeAndTheirChoices() {
        show(form())
        rule.onNodeWithTag("wallet_send_address").assertIsDisplayed()
        rule.onNodeWithTag("wallet_send_scan_button").assertIsDisplayed()
        rule.onNodeWithTag("wallet_send_amount").assertIsDisplayed()
        rule.onNodeWithTag("wallet_send_all").performScrollTo().assertIsDisplayed()
        for (t in listOf("slow", "normal", "fast", "custom")) rule.onNodeWithTag("wallet_send_fee_$t").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Slow").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("about a day, 1 sat/vB").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("about an hour, 5 sat/vB").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("about 20 minutes, 12 sat/vB").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Include unconfirmed coins").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send all").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Review").assertIsEnabled()
    }

    @Test
    fun reviewIsOffUntilThereIsAPayeeAnAmountAndAFee() {
        show(form(address = ""))
        rule.onNodeWithText("Review").assertIsNotEnabled()
    }

    @Test
    fun theWrongNetworkTextIsThePlans() {
        show(form(address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", issue = AddressIssue.WrongNetwork(AddressFamily.MAINNET)))
        rule.onNodeWithTag("wallet_send_address_issue", useUnmergedTree = true).assertTextEquals("This is a mainnet address. This wallet is on Testnet4.")
        rule.onNodeWithText("Review").assertIsNotEnabled()
    }

    @Test
    fun aTestAddressInAMainnetWalletNamesTheTestNetwork() {
        show(form(issue = AddressIssue.WrongNetwork(AddressFamily.TEST)).copy(network = Network.MAINNET))
        rule.onNodeWithTag("wallet_send_address_issue", useUnmergedTree = true).assertTextEquals("This is a test network address. This wallet is on mainnet.")
    }

    @Test
    fun typingAndPastingGoToTheViewModel() {
        val typed = mutableListOf<String>()
        show(form(address = ""), SendActions(onAddress = { typed += it }))
        rule.onNodeWithTag("wallet_send_address").performTextInput("  tb1q ")
        assertTrue(typed.isNotEmpty())
    }

    @Test
    fun theFeeChoicesAndCustomFieldAreWired() {
        val picked = mutableListOf<FeeChoice>()
        show(form().copy(feeChoice = FeeChoice.CUSTOM, customFee = "1500"), SendActions(onFee = { picked += it }))
        rule.onNodeWithTag("wallet_send_fee_fast").performScrollTo().performClick()
        assertEquals(listOf(FeeChoice.FAST), picked)
        rule.onNodeWithTag("wallet_send_fee_custom_field").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Enter a fee between 1 and 1,000 sat/vB.").assertIsDisplayed()
    }

    @Test
    fun withoutEstimatesTheFixedChoicesAreOffAndCustomRemains() {
        show(form(estimates = null).copy(estimatesFailed = true))
        rule.onNodeWithTag("wallet_send_fees_failed").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("wallet_send_fee_fast").assertIsNotEnabled()
        rule.onNodeWithTag("wallet_send_fee_custom").assertIsEnabled()
    }

    @Test
    fun aFeeMuchHigherThanNeededAsksWithTheDraftsWords() {
        var paid = false
        show(form().copy(bigFeeAsk = BigFee.MUCH_HIGHER), SendActions(onConfirmBigFee = { paid = true }))
        rule.onNodeWithText("This fee is much higher than needed right now. Pay it anyway?").assertIsDisplayed()
        rule.onNodeWithText("Pay it anyway").performClick()
        assertTrue(paid)
    }

    @Test
    fun theTenPercentGuardHasItsOwnText() {
        var paid = false
        show(form().copy(bigFeeAsk = BigFee.TEN_PERCENT), SendActions(onConfirmBigFee = { paid = true }))
        rule.onNodeWithText("The fee is more than 10% of what you're sending. Pay it anyway?").assertIsDisplayed()
        rule.onNodeWithText("Pay it anyway").performClick()
        assertTrue(paid)
    }

    @Test
    fun theReviewAsksAboutAFeeOverTenPercentWithTheSameDialog() {
        show(review().copy(bigFeeAsk = BigFee.TEN_PERCENT))
        rule.onNodeWithTag("wallet_send_big_fee").assertIsDisplayed()
    }

    @Test
    fun aRefusedLinkIsSaidPlainly() {
        show(form().copy(linkRefused = true))
        rule.onNodeWithTag("wallet_send_link_refused").performScrollTo()
            .assertTextEquals("This payment link has something VettID can't safely use, so nothing was filled in. Check it with whoever sent it.")
    }

    @Test
    fun eachRecentPaymentNoticeHasItsTextAndSendAnywayGoesOn() {
        var acknowledged = 0
        val cases = mapOf(
            RecentSends.WAITING to "A payment you approved is waiting for confirmation.",
            RecentSends.MAY_HAVE_GONE to "A payment you approved may have gone through.\n\nIt may already be on the network. Check before you send again.",
            RecentSends.UNREADABLE to "VettID couldn't check your recent payments. One you approved may still be on its way. Check before you send again.",
        )
        for ((notice, text) in cases) {
            show(form().copy(recentNotice = notice), SendActions(onAcknowledgeRecent = { acknowledged++ }))
            rule.onNodeWithText(text).assertIsDisplayed()
            rule.onNodeWithText("Send anyway").performClick()
        }
        assertEquals(3, acknowledged)
    }

    @Test
    fun sendAllDisablesTheAmount() {
        show(form().copy(sendAll = true, amountText = ""))
        rule.onNodeWithTag("wallet_send_amount").assertIsNotEnabled()
    }

    // --- the holds ---

    @Test
    fun eachHoldShowsItsOwnNotice() {
        val cases = mapOf(
            SendBlock.NOT_BACKED_UP to "Not backed up. If your vault is lost, these coins are lost too.",
            SendBlock.CAPS_PENDING to "Sending is off until your spending limit is set.",
            SendBlock.SETTINGS_UNREADABLE to "This phone can't read its wallet settings. Sending is off until it can.",
            SendBlock.CAPS_DUPLICATE to "Your vault holds another copy of this wallet. Delete one copy before setting a limit.",
            SendBlock.MISMATCH to "This wallet's addresses don't match your vault's. Nothing was sent.",
            SendBlock.SIGN_UNSETTLED to "Your vault may have signed a payment from this wallet that this phone never heard back about. Check before you send again. This wallet can send again once your vault has settled it.",
        )
        for ((block, text) in cases) {
            show(form().copy(block = block))
            rule.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test
    fun theBackupGateOffersTheBackupAndTheOthersTryAgain() {
        var backedUp = false
        show(form().copy(block = SendBlock.NOT_BACKED_UP), SendActions(onBackUp = { backedUp = true }))
        rule.onNodeWithText("Back up this wallet").performClick()
        assertTrue(backedUp)
    }

    @Test
    fun aHoldThatCanLiftOffersTryAgain() {
        var again = false
        show(form().copy(block = SendBlock.CAPS_PENDING), SendActions(onLoad = { again = true }))
        rule.onNodeWithText("Try again").performClick()
        assertTrue(again)
    }

    @Test
    fun theNetworkQuestionIsAskedWhenTheWalletHasNone() {
        var chosen: Network? = null
        show(form().copy(block = SendBlock.NEEDS_NETWORK), SendActions(onChooseNetwork = { chosen = it }))
        rule.onNodeWithText("Which test network is this wallet on?").assertIsDisplayed()
        rule.onNodeWithTag("wallet_choose_testnet4").performClick()
        assertEquals(Network.TESTNET4, chosen)
    }

    // --- the review ---

    @Test
    fun theReviewIsTheVaultsSummaryWithTheFeeInSatsAndSatPerVb() {
        show(review(sampleReview(toFeeSats = 200)))
        rule.onNodeWithTag("wallet_send_review_address").assertTextEquals(PAYEE)
        rule.onNodeWithTag("wallet_send_review_amount").assertTextEquals("0.0004 tBTC")
        rule.onNodeWithTag("wallet_send_review_change").assertTextEquals("0.000588 tBTC")
        rule.onNodeWithTag("wallet_send_review_fee").assertTextEquals("1,200 sats (6 sat/vB)")
        rule.onNodeWithTag("wallet_send_review_total").assertTextEquals("0.000412 tBTC")
        rule.onNodeWithTag("wallet_send_review_dust").assertTextEquals("200 sats were added to the fee, since sending them back as change wasn't worth it.")
    }

    @Test
    fun theApproveSentenceAndButtonAreThePlans() {
        show(review())
        rule.onNodeWithTag("wallet_send_review_approve").assertTextEquals("Send 0.0004 tBTC to $PAYEE? Fee 1,200 sats.")
        rule.onNodeWithText("Approve with password").assertIsNotEnabled()
        rule.onNodeWithTag("wallet_send_password").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun approveIsOnceAPasswordIsTyped() {
        var approved = false
        show(review(password = "pw"), SendActions(onApprove = { approved = true }))
        rule.onNodeWithText("Approve with password").assertIsEnabled().performClick()
        assertTrue(approved)
    }

    @Test
    fun theLimitLineIsOnlyThereWhenTheViewModelHasOne() {
        show(review(sampleReview().let { r ->
            Review(r.address, r.payeeSats, r.changeSats, r.feeSats, r.vsize, r.totalOutSats, r.toFeeSats, r.sendAll, r.leftBehind, true, null, LimitLine(41_200, 1_208_800))
        }))
        rule.onNodeWithTag("wallet_send_review_limit")
            .assertTextEquals("Counts toward your limit for the last 24 hours: 41,200 sats. Left after this: 1,208,800 sats")
    }

    @Test
    fun aVaultWithoutCapsShowsNothingAboutLimits() {
        show(review(sampleReview(hasCap = false)))
        assertEquals(0, rule.onAllNodesWithText("limit", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun aCapRefusalInTheInspectReplacesThePasswordWithItsText() {
        var changed = false
        var limits = false
        show(
            review(sampleReview(capRefusal = CapText(CapReason.PER_TX, 30_000, null, null))),
            SendActions(onEdit = { changed = true }, onLimits = { limits = true }),
        )
        rule.onNodeWithTag("wallet_send_review_cap").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("This payment is more than the 30,000 sats you allow per payment. Nothing was signed.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Approve with password").fetchSemanticsNodes().size)
        rule.onNodeWithText("Change the amount").performClick()
        rule.onNodeWithText("Spending limits").performClick()
        assertTrue(changed && limits)
    }

    @Test
    fun aSendAllThatLeftCoinsBehindSaysSo() {
        show(review(Review(PAYEE, 99_000, 0, 1_000, 200, 100_000, 0, true, 2, true)))
        rule.onNodeWithTag("wallet_send_review_left").assertTextEquals("2 coins were left out.")
        show(review(Review(PAYEE, 99_000, 0, 1_000, 200, 100_000, 0, true, 1, true)))
        rule.onNodeWithTag("wallet_send_review_left").assertTextEquals("1 coin was left out.")
    }

    // --- the endings ---

    @Test
    fun sentSaysWhenItWillConfirm() {
        show(done(SendOutcome.Sent(ConfirmTime.HOUR)))
        rule.onNodeWithText("Sent. It will confirm in about an hour.").assertIsDisplayed()
        rule.onNodeWithText("Done").assertIsDisplayed()
    }

    @Test
    fun theTimesAreTheTargets() {
        for ((time, text) in listOf(ConfirmTime.MINUTES_20 to "20 minutes", ConfirmTime.HOUR to "an hour", ConfirmTime.DAY to "a day")) {
            show(done(SendOutcome.Sent(time)))
            rule.onNodeWithText("Sent. It will confirm in about $text.").assertIsDisplayed()
        }
    }

    @Test
    fun aFailedBroadcastNamesTheHostAndOffersTryAgainAndAnotherServer() {
        var again = false
        var server = false
        show(
            done(SendOutcome.BroadcastFailed("orbit.example", BroadcastReason.FEE_TOO_LOW)),
            SendActions(onRetry = { again = true }, onChainServer = { server = true }),
        )
        rule.onNodeWithTag("wallet_send_broadcast_failed")
            .assertTextEquals("Signed, but orbit.example didn't accept it: the fee is too low. Try again, or choose another server.")
        rule.onNodeWithText("Try again").performClick()
        rule.onNodeWithText("Choose another server").performClick()
        assertTrue(again && server)
        rule.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun aBroadcastThatGotNoAnswerOffersNoDiscardUntilTheServerSaidNo() {
        show(done(SendOutcome.BroadcastFailed("orbit.example", BroadcastReason.NO_ANSWER)).copy(discardLocked = true, checking = true))
        rule.onNodeWithTag("wallet_send_checking").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Discard").fetchSemanticsNodes().size)
        show(done(SendOutcome.BroadcastFailed("orbit.example", BroadcastReason.NO_ANSWER)).copy(discardLocked = true))
        rule.onNodeWithTag("wallet_send_may_be_sent").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Discard").fetchSemanticsNodes().size)
    }

    @Test
    fun afterTheServerSaidNoDiscardIsOffered() {
        show(done(SendOutcome.BroadcastFailed("orbit.example", BroadcastReason.NO_ANSWER)))
        rule.onNodeWithText("Discard").assertIsDisplayed()
    }

    @Test
    fun anAlreadySignedPaymentThatMayBeOnTheNetworkOffersNoDiscardEither() {
        show(done(SendOutcome.AlreadySigned).copy(discardLocked = true))
        assertEquals(0, rule.onAllNodesWithText("Discard").fetchSemanticsNodes().size)
        rule.onNodeWithText("Try again").assertIsDisplayed()
        show(done(SendOutcome.Unsent).copy(discardLocked = true))
        assertEquals(0, rule.onAllNodesWithText("Discard").fetchSemanticsNodes().size)
    }

    @Test
    fun withoutAHostTheSentenceNamesTheChainServer() {
        show(done(SendOutcome.BroadcastFailed(null, BroadcastReason.NO_SERVER)))
        rule.onNodeWithTag("wallet_send_broadcast_failed")
            .assertTextEquals("Signed, but the chain server didn't accept it: no server could be used. Try again, or choose another server.")
    }

    @Test
    fun everyEndingThatMayHaveSignedOffersDiscard() {
        for (o in listOf(SendOutcome.SignedDiffers, SendOutcome.AlreadySigned, SendOutcome.Unanswered, SendOutcome.Lapsed, SendOutcome.SignedUnreadable, SendOutcome.Unsent)) {
            show(done(o))
            rule.onNodeWithText("Discard").assertIsDisplayed()
        }
    }

    @Test
    fun aLostAnswerTellsThePaymentMayHaveBeenSigned() {
        show(done(SendOutcome.Unanswered))
        rule.onNodeWithTag("wallet_send_unanswered")
            .assertTextEquals("Your vault hasn't answered yet. The payment may have been signed. Try again asks once more; it will not be signed twice.")
        rule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test
    fun anOkThatDoesNotReadSaysItMayHaveBeenSigned() {
        show(done(SendOutcome.SignedUnreadable))
        rule.onNodeWithTag("wallet_send_unreadable")
            .assertTextEquals("Your vault answered, but this phone couldn't read the signed payment. It may have been signed: check your wallet before you send again.")
    }

    @Test
    fun theLapsedAndTheOtherNoticesHaveTheirTexts() {
        show(done(SendOutcome.Lapsed))
        rule.onNodeWithTag("wallet_send_lapsed")
            .assertTextEquals("Your vault didn't answer in time and will not sign this payment now. It may have been signed before that: check your wallet before you send again.")
    }

    @Test
    fun discardAsksBeforeItDrops() {
        var dropped = false
        show(done(SendOutcome.AlreadySigned).copy(discardAsk = true), SendActions(onDiscard = { dropped = true }))
        rule.onNodeWithText("Discard this payment?").assertIsDisplayed()
        rule.onNodeWithTag("wallet_send_discard_confirm").assertIsDisplayed()
        rule.onAllNodesWithText("Discard").onLast().performClick()
        assertTrue(dropped)
    }

    // --- cap_exceeded ---

    private fun textOf(tag: String) =
        rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)!!.joinToString("") { it.text }

    private fun capText(cap: CapText): String {
        show(done(SendOutcome.CapExceeded(cap)))
        rule.onNodeWithTag("wallet_send_cap").assertIsDisplayed()
        return textOf("wallet_send_cap")
    }

    @Test
    fun perTxIsThePlansTextWithItsTwoButtons() {
        var amount = false
        show(done(SendOutcome.CapExceeded(CapText(CapReason.PER_TX, 500_000, null, null))), SendActions(onStartOver = { amount = true }))
        rule.onNodeWithTag("wallet_send_cap").assertTextEquals("This payment is more than the 500,000 sats you allow per payment. Nothing was signed.")
        rule.onNodeWithText("Spending limits").assertIsDisplayed()
        rule.onNodeWithText("Change the amount").performClick()
        assertTrue(amount)
    }

    @Test
    fun perPeriodWithRoomAndATime() {
        val at = Instant.parse("2026-11-02T09:00:00Z")
        val text = capText(CapText(CapReason.PER_PERIOD, 2_000_000, 300_000, at))
        val expected = "This payment would take you over your limit of 2,000,000 sats in any 24 hours. You can send up to 300,000 sats now. " +
            "You can send this at ${SendFormat.whenAt(at)}."
        assertEquals(expected, text)
    }

    @Test
    fun perPeriodWithNothingLeftAndNoTimeEver() {
        for (remaining in listOf(null, 0L)) {
            val text = capText(CapText(CapReason.PER_PERIOD, 2_000_000, remaining, null))
            assertEquals(
                "This payment would take you over your limit of 2,000,000 sats in any 24 hours. You can't send anything more right now. " +
                    "This is more than your 24-hour limit allows at any time.",
                text,
            )
        }
    }

    // --- invalid_psbt ---

    private fun psbtTexts(reason: String, alarm: Boolean = false): String {
        show(done(SendOutcome.InvalidPsbt(reason, alarm)))
        val tag = if (alarm) "wallet_send_psbt_alarm" else "wallet_send_psbt"
        return textOf(tag)
    }

    @Test
    fun feeRateAndOutputAmountHaveTheirOwnTexts() {
        assertEquals("The fee is above the most your vault will sign (1,000 sat/vB).", psbtTexts("fee_rate"))
    }

    @Test
    fun outputAmountHasItsOwnText() {
        assertEquals("One amount is below 330 sats, too small to send.", psbtTexts("output_amount"))
    }

    @Test
    fun theTechnicalReasonsAreOneTextWithTheReasonUnderDetails() {
        for (reason in PsbtTexts.TECHNICAL) {
            val vm = PsbtTexts.of(reason)
            assertEquals(PsbtText.TECHNICAL, vm)
        }
        assertEquals("VettID built a payment your vault won't sign. Nothing was signed. Please report this.", psbtTexts("derivation_key"))
        rule.onNodeWithText("Details").assertIsDisplayed()
        rule.onNodeWithTag("wallet_send_psbt_details").assertTextEquals("derivation_key")
    }

    @Test
    fun wrongSeedIsAnAlarm() {
        val text = psbtTexts("wrong_seed", alarm = true)
        assertTrue(text.endsWith("Your vault's recovery phrase for this wallet doesn't match its addresses. Nothing was signed. Contact VettID."))
    }

    @Test
    fun anyOtherReasonIsNamedInBrackets() {
        assertEquals("Your vault would not sign this payment (new_reason).", psbtTexts("new_reason"))
    }

    @Test
    fun theAppsOwnCheckOfWhatItBuiltIsTheTechnicalText() {
        assertEquals(PsbtText.TECHNICAL, PsbtTexts.of("anything", byVault = false))
        assertEquals(PsbtText.OTHER, PsbtTexts.of("anything", byVault = true))
        assertEquals(PsbtText.FEE_RATE, PsbtTexts.of("fee_rate", byVault = false))
    }

    // --- the form's own problems ---

    @Test
    fun theDifferentPaymentTextIsThePlans() {
        show(form().copy(problem = SendProblem.Different))
        rule.onNodeWithText("Your vault saw a different payment from the one you entered. Nothing was signed.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun aSelfSendHasItsOwnText() {
        show(form().copy(problem = SendProblem.SelfSend))
        rule.onNodeWithText("This address is one of this wallet's own. Nothing was sent.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun notEnoughNamesWhatThereIsAndWhatIsNeeded() {
        show(form().copy(problem = SendProblem.NotEnough(12_000, 41_200)))
        rule.onNodeWithText("You have 12,000 sats you can spend and this needs 41,200 sats. Unconfirmed coins are left out unless you include them.")
            .performScrollTo().assertIsDisplayed()
        // With unconfirmed coins already included the sentence about them is not shown.
        show(form().copy(problem = SendProblem.NotEnough(12_000, 41_200), includeUnconfirmed = true))
        rule.onNodeWithText("You have 12,000 sats you can spend and this needs 41,200 sats.").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun anAlreadySignedPaymentSaysTheRightThingInBothStates() {
        show(done(SendOutcome.AlreadySigned))
        rule.onNodeWithTag("wallet_send_already").assertTextEquals(
            "This payment was already sent to your vault to be signed. Signing it again could count it twice toward your limit. " +
                "Discard it and start again if you want to send.",
        )
        show(done(SendOutcome.AlreadySigned).copy(discardLocked = true))
        rule.onNodeWithTag("wallet_send_already").assertTextEquals(
            "This payment was already sent to your vault to be signed. Signing it again could count it twice toward your limit. " +
                "Discard is offered once a chain server says the payment isn't there.",
        )
    }

    @Test
    fun aBroadcastThatGotNoAnswerSaysSoAndNamesTheHost() {
        show(done(SendOutcome.BroadcastFailed("orbit.example", BroadcastReason.NO_ANSWER)).copy(discardLocked = true))
        rule.onNodeWithTag("wallet_send_broadcast_failed")
            .assertTextEquals("Signed, but orbit.example didn't answer. The payment may have gone through.")
    }

    @Test
    fun aTimeIsWrittenInTheMembersZoneAndLanguage() {
        val at = Instant.parse("2026-11-02T09:05:00Z")
        assertEquals("Nov 2, 2026, 9:05 AM", SendFormat.whenAt(at, Locale.US, ZoneOffset.UTC).replace(' ', ' '))
    }

    @Test
    fun theScreenComposesWithItsSecureWindow() {
        // Without `secure = false` the screen holds the window secure (`SecureWindow()`, as the phrase screens do).
        rule.setContent { SendScreen(form(), SendActions()) }
        rule.onNodeWithTag("wallet_send_form").assertIsDisplayed()
    }
}
