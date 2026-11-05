package com.vettid.feature.history

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vettid.core.data.vault.AuditRecord
import com.vettid.core.ui.components.ShellChrome
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

/** The connection picker's body is a LazyColumn: the dialog wrapper's scroll must not wrap it (infinite height constraints throw). */
@RunWith(RobolectricTestRunner::class)
class ConnectionDialogTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun theConnectionPickerOpensWithItsList() {
        val chrome = ShellChrome(accountName = "Me", onMenuClick = {}, onAvatarClick = {})
        val rows = listOf(AuditRecord("e1", 1, Instant.now().minusSeconds(60), "vault.unlocked"))
        rule.setContent { HistoryScreen(HistoryUiState(rows, loading = false), chrome, HistoryActions()) }
        rule.onNodeWithTag("history_connection").performClick()
        rule.onNodeWithText("Connection").assertExists()
    }
}
