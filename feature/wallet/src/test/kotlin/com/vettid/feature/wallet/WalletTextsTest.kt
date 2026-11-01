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
            R.string.wallet_empty_body to "Keep bitcoin in your vault. Payments need your password.",
            R.string.wallet_test_banner to "Test network: these coins have no value.",
            R.string.wallet_password_body to "Your password protects this wallet. You will need it for every payment.",
            R.string.wallet_phrase_body to "Write these words on paper, in order, and keep them somewhere safe. Anyone with them can take your coins. VettID never shows them to anyone else.",
            R.string.wallet_phrase_no_screenshots to "No screenshots: this screen is hidden from screenshots and the app switcher.",
            R.string.wallet_backup_body to "Not backed up. If your vault is lost, these coins are lost too.",
            R.string.wallet_row_not_backed_up to "Not backed up",
            R.string.wallet_backed_up to "Backed up",
            R.string.wallet_check_show_again to "Show the words again",
        )
        for ((res, text) in drafts) assertEquals(text, r.getString(res))
        assertEquals("Which is word 4?", r.getString(R.string.wallet_check_which, 4))
        assertEquals("That's not word 11. Look at your paper again.", r.getString(R.string.wallet_check_wrong, 11))
    }
}
