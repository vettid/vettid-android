// Test data: long literal rows are clearer than wrapped ones.
@file:Suppress("MaxLineLength")

package com.vettid.feature.wallet

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
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
}
