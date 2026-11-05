package com.vettid.feature.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vettid.core.data.items.ItemSummary
import com.vettid.core.data.items.Sensitivity
import com.vettid.core.data.vault.AccountInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The item picker's body is a LazyColumn: the dialog wrapper's scroll must not wrap it (infinite height constraints throw). */
@RunWith(RobolectricTestRunner::class)
class ProfileItemPickerDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun thePickerOpensWithItsList() {
        val item = ItemSummary("i1", 1, "Passport", "id", Sensitivity.DATA)
        val account = AccountInfo("a***@example.org", state = "member", firstName = "Ada", lastName = "Lovelace")
        rule.setContent {
            SharedProfileContent(SharedProfileUiState(account, null, candidates = listOf(item), pickingItem = true), SharedProfileActions())
        }
        rule.onNodeWithText("Passport").assertExists()
    }
}
