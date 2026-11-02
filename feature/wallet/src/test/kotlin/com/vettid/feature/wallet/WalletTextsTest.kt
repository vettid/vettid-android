// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The texts that are the plan's drafts (ANDROID-PLAN 0.1.111 §4 "Texts (drafts)") and its screens table, word for word. Not
 * approved yet: the owner approves every wallet text before merge (the PR's texts table); a change here is a change to that table.
 */
@RunWith(RobolectricTestRunner::class)
class WalletTextsTest {
    private val r = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun theDraftsAreTheSpecsWords() {
        val drafts = listOf(
            R.string.wallet_empty_title to "No wallets yet",
            R.string.wallet_test_banner to "Test network: these coins have no value.",
            R.string.wallet_password_body to "Enter your VettID password. It protects this wallet: you will need it for every payment.",
            R.string.wallet_limit to "Your vault holds 16 wallets, the most it can.",
            R.string.wallet_not_recorded to "Not recorded yet",
            R.string.wallet_name_blank to "Enter a name.",
            R.string.wallet_name_too_long to "That name is too long.",
            R.string.wallet_phrase_passphrase_note to "You need this passphrase as well as the words to recover this wallet.",
            R.string.wallet_empty_body to "Keep bitcoin in your vault. Payments need your VettID password.",
            R.string.wallet_phrase_body to "Write these words on paper, in order, and keep them somewhere safe. Anyone with them can take your coins. VettID never shows them to anyone else.",
            R.string.wallet_phrase_no_screenshots to "This screen is hidden from screenshots and the app switcher.",
            R.string.wallet_backup_body to "Not backed up. If your vault is lost, these coins are lost too.",
            R.string.wallet_row_not_backed_up to "Not backed up",
            R.string.wallet_backed_up to "Backed up",
            R.string.wallet_check_show_again to "Show the words again",
            R.string.wallet_network_ask to "Which test network is this wallet on?",
            R.string.wallet_address_mismatch to "This wallet's addresses don't match your vault's. Nothing was sent.",
            R.string.wallet_server_failed to "That server didn't answer.",
            R.string.wallet_privacy_note to "To show your balance and send, VettID asks this server about your wallet's addresses. The server sees those addresses, and can tell they belong together, and it sees this phone's internet address and when you look. VettID's own servers see none of it. Use your own server to keep this to yourself.",
            R.string.wallet_testnet3_unsupported to "VettID's staging app does not connect to testnet3.",
            R.string.wallet_network_not_offered to "This version of VettID doesn't connect to this network.",
            R.string.wallet_server_saved to "Saved. VettID now uses this server.",
            R.string.wallet_looking_up to "Looking up…",
            R.string.wallet_amount_bad to "Enter an amount above 0, with at most 8 decimal places.",
            R.string.wallet_delete_password to "Enter your VettID password to delete %1\$s.",
            // The screens table's words.
            R.string.wallet_menu_address_type to "Receiving address type",
            R.string.wallet_type_taproot to "Taproot (recommended)",
            R.string.wallet_type_segwit to "Native SegWit",
            R.string.wallet_menu_chain_server to "Chain server",
            R.string.wallet_menu_details to "Details",
            R.string.wallet_menu_delete to "Delete wallet",
            R.string.wallet_account_p2tr to "Taproot address",
            R.string.wallet_new_address to "New address",
            R.string.wallet_server_use_own to "Use my own server",
            R.string.wallet_server_reset to "Reset to default",
            // Spending limits and Import (W1d): the plan's Texts (drafts), the screens table and the 0.1.74 spending limits.
            R.string.wallet_limits_title to "Set a spending limit?",
            R.string.wallet_limits_body to "Your vault will refuse any payment above it. You can lower it any time; raising it needs your PIN and password.",
            R.string.wallet_limits_none to "No limit",
            R.string.wallet_limit_per_tx to "Most per payment",
            R.string.wallet_limit_per_period to "Most in any 24 hours",
            R.string.wallet_pin_too to "To protect your spending limit, enter your PIN too.",
            R.string.wallet_import to "Import a wallet",
            R.string.wallet_import_invalid to "These words aren't a valid recovery phrase. Check each word and the order.",
            R.string.wallet_import_advanced to "Advanced",
            R.string.wallet_import_passphrase_note to "Only if you set one when the wallet was made. Letters, digits and symbols from a standard keyboard only.",
            // The Spending limits screen (W1d-2): the plan's Texts (drafts) and "The screen".
            R.string.wallet_menu_limits to "Spending limits",
            R.string.wallet_limits_lower_button to "Lower a limit",
            R.string.wallet_limits_raise_button to "Raise or remove a limit",
            R.string.wallet_limits_remove_title to "Remove the limit?",
            R.string.wallet_limits_enter_secrets to "Enter your PIN and password.",
        )
        for ((res, text) in drafts) assertEquals(text, r.getString(res))
        assertEquals("Used in the last 24 hours: 750,000 sats of 2,000,000 sats", r.getString(R.string.wallet_limits_used, "750,000 sats", "2,000,000 sats"))
        assertEquals(
            "Your vault holds another copy of this wallet. Delete one copy before setting a limit.",
            r.getString(R.string.wallet_limits_duplicate),
        )
        assertEquals("Remove the limits?", r.getString(R.string.wallet_limits_remove_title_both))
        assertEquals("Lower the limit to 400,000 sats?", r.getString(R.string.wallet_limits_lower_title, "400,000 sats"))
        assertEquals("Raise the limit from 1 sat to 2 sats?", r.getString(R.string.wallet_limits_raise_title, "1 sat", "2 sats"))
        assertEquals("Which is word 4?", r.getString(R.string.wallet_check_which, 4))
        assertEquals("That's not word 11. Look at your paper again.", r.getString(R.string.wallet_check_wrong, 11))
        assertEquals("That server isn't on Signet.", r.getString(R.string.wallet_server_wrong_network, "Signet"))
        assertEquals("Only send Signet bitcoin to this address.", r.getString(R.string.wallet_only_send, "Signet"))
        assertEquals("Using mempool.space", r.getString(R.string.wallet_details_using, "mempool.space"))
        assertEquals("+ 0.001 incoming", r.getString(R.string.wallet_incoming, "0.001"))
        assertEquals("− 0.001 outgoing", r.getString(R.string.wallet_outgoing, "0.001"))
    }

    @Test
    fun theDeleteWarningIsThePlansTextInTwoParts() {
        // The plan's one text: "Delete {name}? Your vault forgets this wallet and its recovery phrase. Coins on it are lost unless you have the recovery phrase written down."
        assertEquals(
            "Delete Savings? Your vault forgets this wallet and its recovery phrase. Coins on it are lost unless you have the recovery phrase written down.",
            r.getString(R.string.wallet_delete_title, "Savings") + " " + r.getString(R.string.wallet_delete_body),
        )
        assertEquals("0.00123 BTC", r.getString(R.string.wallet_balance_btc, BtcFormat.btc(123_000)))
        assertEquals("123,000 sats", r.getString(R.string.wallet_balance_sats, BtcFormat.sats(123_000, java.util.Locale.US)))
    }

    @Test
    fun theSendDraftsAreThePlansWordsAndNoneIsMarkedApproved() {
        // ANDROID-PLAN §4 "Sending", "Texts (drafts)" and the cap_exceeded / invalid_psbt paragraphs, word for word.
        assertEquals("This is a mainnet address. This wallet is on Signet.", r.getString(R.string.wallet_send_wrong_network, "mainnet", "Signet"))
        assertEquals("Your vault saw a different payment from the one you entered. Nothing was signed.", r.getString(R.string.wallet_send_mismatch))
        assertEquals("Send 0.0012 tBTC to tb1qexample? Fee 1,200 sats.", r.getString(R.string.wallet_send_approve, "0.0012 tBTC", "tb1qexample", "1,200 sats"))
        assertEquals("Approve with password", r.getString(R.string.wallet_send_approve_button))
        assertEquals(
            "Signed, but mempool.space didn't accept it: the fee is too low. Try again, or choose another server.",
            r.getString(R.string.wallet_send_broadcast_failed, "mempool.space", r.getString(R.string.wallet_send_reason_fee_low)),
        )
        assertEquals(
            "Counts toward your limit for the last 24 hours: 41,200 sats. Left after this: 1,208,800 sats",
            r.getString(R.string.wallet_send_limit_review, "41,200 sats", "1,208,800 sats"),
        )
        assertEquals("This fee is much higher than needed right now. Pay it anyway?", r.getString(R.string.wallet_send_fee_much_higher))
        assertEquals("Sent. It will confirm in about an hour.", r.getString(R.string.wallet_send_sent, r.getString(R.string.wallet_send_time_hour)))
        assertEquals("Include unconfirmed coins", r.getString(R.string.wallet_send_unconfirmed))
        assertEquals("Send all", r.getString(R.string.wallet_send_all))
        assertEquals(listOf("Slow", "Normal", "Fast", "Custom"), listOf(R.string.wallet_send_fee_slow, R.string.wallet_send_fee_normal, R.string.wallet_send_fee_fast, R.string.wallet_send_fee_custom).map(r::getString))
        // cap_exceeded.
        assertEquals("This payment is more than the 500,000 sats you allow per payment. Nothing was signed.", r.getString(R.string.wallet_cap_per_tx, "500,000 sats"))
        assertEquals(
            "This payment would take you over your limit of 2,000,000 sats in any 24 hours. You can send up to 300,000 sats now.",
            r.getString(R.string.wallet_cap_per_period, "2,000,000 sats") + " " + r.getString(R.string.wallet_cap_remaining, "300,000 sats"),
        )
        assertEquals("You can't send anything more right now.", r.getString(R.string.wallet_cap_none))
        assertEquals("You can send this at 9:00 AM.", r.getString(R.string.wallet_cap_after, "9:00 AM"))
        assertEquals("This is more than your 24-hour limit allows at any time.", r.getString(R.string.wallet_cap_never))
        assertEquals("Change the amount", r.getString(R.string.wallet_cap_change_amount))
        assertEquals("Spending limits", r.getString(R.string.wallet_menu_limits))
        // invalid_psbt.
        assertEquals("The fee is above the most your vault will sign (1,000 sat/vB).", r.getString(R.string.wallet_psbt_fee_rate))
        assertEquals("One amount is below 330 sats, too small to send.", r.getString(R.string.wallet_psbt_output_amount))
        assertEquals("VettID built a payment your vault won't sign. Nothing was signed. Please report this.", r.getString(R.string.wallet_psbt_technical))
        assertEquals("Details", r.getString(R.string.wallet_psbt_details))
        assertEquals(
            "Your vault's recovery phrase for this wallet doesn't match its addresses. Nothing was signed. Contact VettID.",
            r.getString(R.string.wallet_psbt_wrong_seed),
        )
        assertEquals("Your vault would not sign this payment (odd_reason).", r.getString(R.string.wallet_psbt_other, "odd_reason"))
    }

    @Test
    fun theFourthReviewsTextsAreAsRevised() {
        assertEquals("The fee, at least 4,400 sats, is more than 10% of what you're sending. Pay it anyway?", r.getString(R.string.wallet_send_fee_ten_percent_estimate, "4,400"))
        assertEquals("A payment you approved is waiting for confirmation: 0.0004 tBTC at 9:05 AM.", r.getString(R.string.wallet_send_recent_waiting_nopayee, "0.0004 tBTC", "9:05 AM"))
        assertEquals("A payment you approved may have gone through: 0.0004 tBTC at 9:05 AM.", r.getString(R.string.wallet_send_recent_gone_nopayee, "0.0004 tBTC", "9:05 AM"))
    }

    @Test
    fun theThirdReviewsTextsAreAsRevised() {
        assertEquals("It may not have gone out, or the server hasn't seen it yet. Check before you send again.", r.getString(R.string.wallet_send_recent_gone_more))
        assertEquals("Checking your recent payments…", r.getString(R.string.wallet_send_recent_checking))
        assertEquals("The fee, 4,400 sats, is more than 10% of what you're sending. Pay it anyway?", r.getString(R.string.wallet_send_fee_ten_percent, "4,400"))
        assertEquals("1 more payment is in the same state.", r.getQuantityString(R.plurals.wallet_send_recent_more, 1, 1))
        assertEquals("3 more payments are in the same state.", r.getQuantityString(R.plurals.wallet_send_recent_more, 3, 3))
    }

    @Test
    fun theSecondReviewsTextsAreAsRevised() {
        assertEquals("Signed, but mempool.space didn't answer. The payment may have gone through.", r.getString(R.string.wallet_send_broadcast_no_answer, "mempool.space"))
        assertEquals("You have 1,000 sats you can spend and this needs 2,000 sats.", r.getString(R.string.wallet_send_not_enough, "1,000", "2,000"))
        assertEquals("Unconfirmed coins are left out unless you include them.", r.getString(R.string.wallet_send_not_enough_unconfirmed))
        assertEquals("330 sats were added to the fee, since sending them back as change wasn't worth it.", r.getString(R.string.wallet_send_review_dust, "330"))
        assertEquals("Discard it and start again if you want to send.", r.getString(R.string.wallet_send_already_signed_discard))
        assertEquals("Discard is offered once a chain server says the payment isn't there.", r.getString(R.string.wallet_send_already_signed_wait))
    }

    @Test
    fun theReviewedSendTextsAreAsRevised() {
        assertEquals("This address is one of this wallet's own. Nothing was sent.", r.getString(R.string.wallet_send_self_send))
        assertEquals("1 coin was left out.", r.getQuantityString(R.plurals.wallet_send_review_left_behind, 1, 1))
        assertEquals("3 coins were left out.", r.getQuantityString(R.plurals.wallet_send_review_left_behind, 3, 3))
        assertEquals(
            "Your vault answered, but this phone couldn't read the signed payment. It may have been signed: check your wallet before you send again.",
            r.getString(R.string.wallet_send_unreadable),
        )
        assertEquals(
            "The payment may have gone through. Discard is offered once a chain server says it doesn't have it.",
            r.getString(R.string.wallet_send_may_be_sent),
        )
    }

    @Test
    fun noSendTextIsMarkedApprovedAndEachIsMarkedDraftNewOrAScreensTableWord() {
        val xml = java.io.File("src/main/res/values/strings.xml").readLines()
        val send = xml.filter { Regex("""name="wallet_(send|cap|psbt)_""").containsMatchIn(it) }
        assertTrue(send.size > 90)
        for (line in send) {
            assertFalse("approved: $line", line.substringAfter("-->", "").isEmpty() && line.substringAfter("<!--").contains("approved"))
            assertTrue("unmarked: $line", Regex("""<!-- (draft|new|screens table|sending paragraph)""").containsMatchIn(line))
        }
    }
}
