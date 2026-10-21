package com.vettid.feature.settings

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.vault.AccountInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Settings account card (ANDROID-PLAN §4 "The member's own profile", owner decision 2026-10-21): the name and the
 * address only while the vault is open; otherwise only the initial tile, without the masked hint.
 */
@RunWith(RobolectricTestRunner::class)
class AccountCardTest {
    @get:Rule
    val rule = createComposeRule()

    private fun show(account: AccountInfo?) = rule.setContent { SettingsContent(SettingsUiState(account = account), SettingsActions()) }

    @Test
    fun whileTheVaultIsNotOpenTheCardShowsNoNameAndNoHint() {
        show(AccountInfo("a***@example.org"))
        rule.onNodeWithTag("account_card").assertExists()
        rule.onNodeWithText("a***@example.org", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theOpenVaultsNameAndAddressAreShown() {
        show(AccountInfo("a***@example.org", state = "member", firstName = "Ada", lastName = "Lovelace", email = "ada@example.org"))
        rule.onNodeWithTag("account_card").assertTextContains("Ada Lovelace", substring = true)
        rule.onNodeWithText("ada@example.org", substring = true, useUnmergedTree = true).assertExists()
    }
}
